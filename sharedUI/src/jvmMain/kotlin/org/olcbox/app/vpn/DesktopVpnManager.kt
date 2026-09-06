package org.olcbox.app.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.DesktopDnsResolver
import org.olcbox.app.vpn.desktop.DesktopProxyController
import org.olcbox.app.vpn.desktop.LinuxPrivilege
import org.olcbox.app.vpn.desktop.LinuxTunController
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import org.olcbox.app.vpn.desktop.PacServer
import org.olcbox.app.vpn.desktop.WindowsTunController
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class DesktopVpnManager private constructor(
    private val locationsRepository: LocationsRepository,
    private val proxyController: DesktopProxyController = DesktopProxyController.current(),
    private val pacServer: PacServer = PacServer()
) : VpnManager {

    constructor(locationsRepository: LocationsRepository) : this(
        locationsRepository = locationsRepository,
        proxyController = DesktopProxyController.current(),
        pacServer = PacServer()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _socksProxySettings = MutableStateFlow(DesktopSocksProxySettings())
    val socksProxySettings: StateFlow<DesktopSocksProxySettings> = _socksProxySettings.asStateFlow()

    private var operationJob: Job? = null
    private var logJob: Job? = null
    private var tunLogJob: Job? = null
    private var processWatchJob: Job? = null
    private var tunProcessWatchJob: Job? = null
    private var process: Process? = null
    private var tunProcess: Process? = null
    private var olcRtcConfigPath: Path? = null
    private var olcRtcBinary: Path? = null
    private var activeDesktopMode: DesktopMode? = null
    private var generation = 0L
    // Rate limit for the post-handover room refresh, so a client cycling through
    // dead rooms cannot turn one fetch per hop into a storm.
    @Volatile
    private var lastSwitchRefreshAt = 0L
    private val linuxTunController = LinuxTunController(::addLog)
    private val windowsTunController = WindowsTunController(::addLog)

    init {
        // Live room-list reload: when the subscription changes while connected,
        // rewrite the olcRTC config file in place. In failover mode olcRTC re-reads
        // it on its next room hop, so new rooms are picked up WITHOUT a restart.
        scope.launch {
            locationsRepository.changes
                .drop(1)
                .collect { rewriteActiveConfigIfConnected() }
        }
    }

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        val requestGeneration = ++generation
        operationJob = scope.launch {
            mutex.withLock {
                if (requestGeneration != generation) return@withLock

                val shouldRestart = _status.value is VpnStatus.Connected ||
                        _status.value is VpnStatus.Connecting ||
                        _status.value is VpnStatus.Reconnecting ||
                        process != null ||
                        tunProcess != null

                if (shouldRestart) {
                    setStatus(VpnStatus.Reconnecting)
                    addLog("Restarting desktop VPN for selected location")
                    stopDesktopMode(finalStatus = false)

                    if (requestGeneration != generation) return@withLock
                }

                startDesktopMode(requestGeneration, isRestart = shouldRestart)
            }
        }
    }

    override fun stopVpn() {
        generation++
        operationJob = scope.launch {
            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }
        }
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.ping(
            locationConfig = locationConfig,
            deviceId = locationsRepository.getDeviceIdentity()
        )
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            deviceId = locationsRepository.getDeviceIdentity()
        )
    }

    override fun subscriptionFetchProxy(): SubscriptionFetchProxy? {
        val currentStatus = status.value
        if (currentStatus !is VpnStatus.Connected &&
            currentStatus !is VpnStatus.Reconnecting
        ) {
            return null
        }

        val socks = _socksProxySettings.value.normalized()
        return SubscriptionFetchProxy(
            host = socks.host,
            port = socks.port,
            username = socks.username,
            password = socks.password
        )
    }

    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        val settings = _socksProxySettings.value.copy(
            port = port,
            username = username,
            password = password
        ).normalized()
        _socksProxySettings.value = settings
        pacServer.updateSocksTarget(
            socksHost = settings.host,
            socksPort = settings.port,
            socksUsername = settings.username,
            socksPassword = settings.password
        )
    }

    fun updateSocksProxySettings(settings: DesktopSocksProxySettings) {
        val normalized = settings.normalized()
        _socksProxySettings.value = normalized
        pacServer.updateSocksTarget(
            socksHost = normalized.host,
            socksPort = normalized.port,
            socksUsername = normalized.username,
            socksPassword = normalized.password
        )
    }

    fun close() {
        runBlocking {
            generation++

            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }

            scope.cancel()
        }
    }

    private suspend fun startDesktopMode(requestGeneration: Long, isRestart: Boolean) {
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)

        val active = locationsRepository.getActiveLocation()
        val location = active?.location?.normalized()

        if (location == null || !location.isComplete()) {
            setStatus(VpnStatus.Error("No active location"))
            addLog("Add a valid location before starting desktop proxy")
            return
        }

        try {
            val ready = CompletableDeferred<Unit>()
            val startupFailure = CompletableDeferred<String>()
            val socksSettings = _socksProxySettings.value.normalized()
            val desktopMode = DesktopMode.from(socksSettings.routingMode)
            activeDesktopMode = desktopMode

            if (desktopMode == DesktopMode.WindowsTun) {
                windowsTunController.ensureAdministratorOrRequestRestart()
            }

            // Read before the TUN exists, so it is the machine's real path out.
            val bindInterfaceIndex = if (desktopMode == DesktopMode.WindowsTun) {
                windowsTunController.defaultRouteInterfaceIndex()
            } else {
                null
            }

            process = startOlcRtcProcessWithFallback(
                location = location,
                socksSettings = socksSettings,
                ready = ready,
                startupFailure = startupFailure,
                logOutput = true,
                privileged = desktopMode == DesktopMode.LinuxTun,
                bindInterfaceIndex = bindInterfaceIndex
            )

            val olcRtcProcess = process ?: error("olcRTC process is missing")
            waitForOlcRtcReady(
                process = olcRtcProcess,
                ready = ready,
                startupFailure = startupFailure,
                socksPort = socksSettings.port,
                requestGeneration = requestGeneration
            )

            if (requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            when (desktopMode) {
                DesktopMode.LinuxTun -> startLinuxTun(socksSettings.port, requestGeneration)
                DesktopMode.WindowsTun -> startWindowsTun(socksSettings.port, requestGeneration)
                DesktopMode.SystemProxy -> startSystemProxy(socksSettings, requestGeneration)
                DesktopMode.LocalSocks -> Unit
            }

            if (!olcRtcProcess.isAlive) {
                error("olcRTC exited before desktop proxy was enabled")
            }

            startProcessExitWatchers(
                desktopMode = desktopMode,
                olcRtcProcess = olcRtcProcess,
                currentTunProcess = tunProcess,
                requestGeneration = requestGeneration
            )

            setStatus(VpnStatus.Connected)
            addLog(
                when (desktopMode) {
                    DesktopMode.LinuxTun -> "Desktop Linux TUN connected"
                    DesktopMode.WindowsTun -> "Desktop Windows TUN connected"
                    DesktopMode.SystemProxy -> "Desktop proxy connected"
                    DesktopMode.LocalSocks -> "Desktop local SOCKS proxy connected"
                }
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                addLog("Desktop start cancelled")
            } else {
                addLog("Desktop start failed: ${e.message}")
            }

            stopDesktopMode(finalStatus = false)

            if (e !is CancellationException && requestGeneration == generation) {
                setStatus(VpnStatus.Error(e.message ?: "Desktop start failed"))
            }
        }
    }

    private suspend fun startLinuxTun(socksPort: Int, requestGeneration: Long) {
        val hevBinary = DesktopNativeAssets.resolveHevSocks5TunnelBinary()
        tunProcess = linuxTunController.start(hevBinary, socksPort)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("hev-socks5-tunnel process is missing"))
    }

    private suspend fun startWindowsTun(socksPort: Int, requestGeneration: Long) {
        val tun2SocksBinary = DesktopNativeAssets.resolveWindowsTun2SocksBinary()
        tunProcess = windowsTunController.start(tun2SocksBinary, socksPort)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("tun2socks process is missing"))
    }

    private suspend fun startSystemProxy(
        socksSettings: DesktopSocksProxySettings,
        requestGeneration: Long
    ) {
        pacServer.start(
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUsername = socksSettings.username,
            socksPassword = socksSettings.password
        )
        proxyController.enable(pacServer.url)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }
    }

    private enum class DesktopMode {
        LinuxTun,
        WindowsTun,
        SystemProxy,
        LocalSocks;

        companion object {
            fun from(mode: DesktopRoutingMode): DesktopMode {
                return when (mode.resolveForCurrentPlatform()) {
                    DesktopRoutingMode.Tun -> when (DesktopPaths.os) {
                        DesktopOs.Linux -> LinuxTun
                        DesktopOs.Windows -> WindowsTun
                        DesktopOs.MacOS,
                        DesktopOs.Other -> SystemProxy
                    }
                    DesktopRoutingMode.SystemProxy -> SystemProxy
                    DesktopRoutingMode.LocalSocks -> LocalSocks
                    DesktopRoutingMode.Auto -> error("Auto desktop mode was not resolved")
                }
            }
        }
    }

    private fun startOlcRtcProcessWithFallback(
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean,
        bindInterfaceIndex: Int?
    ): Process {
        val binaries = DesktopNativeAssets.resolveOlcRtcBinaryCandidates()
        val dnsServer = location.dnsServer.ifBlank { DesktopDnsResolver.current() }
        var lastException: Exception? = null

        addLog("Using DNS server $dnsServer for olcRTC")

        for (binary in binaries) {
            try {
                return startOlcRtcProcess(
                    binary = binary,
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = logOutput,
                    privileged = privileged,
                    bindInterfaceIndex = bindInterfaceIndex,
                    dnsServer = dnsServer
                )
            } catch (e: Exception) {
                lastException = e

                if (binary == binaries.last()) break

                addLog("olcRTC start failed for ${binary.fileName}: ${e.message}. Retrying with fallback binary.")
            }
        }

        throw lastException ?: error("olcRTC binary failed to start")
    }

    private suspend fun stopDesktopMode(finalStatus: Boolean) {
        if (_status.value is VpnStatus.Disconnected && process == null && tunProcess == null) {
            cancelProcessJobs()
            return
        }

        setStatus(VpnStatus.Stopping)
        cancelProcessJobs()

        val stoppedMode = activeDesktopMode
        when (stoppedMode) {
            DesktopMode.LinuxTun -> {
                runCatching {
                    linuxTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Linux TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopMode.WindowsTun -> {
                runCatching {
                    windowsTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Windows TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopMode.SystemProxy -> {
                runCatching {
                    proxyController.restore()
                }.onFailure {
                    addLog("Proxy restore failed: ${it.message}")
                }
            }
            DesktopMode.LocalSocks,
            null -> Unit
        }

        if (stoppedMode == DesktopMode.SystemProxy) {
            pacServer.stop()
        }
        activeDesktopMode = null

        stopProcess(process)
        process = null
        deleteOlcRtcConfig()

        if (finalStatus) {
            setStatus(VpnStatus.Disconnected)
            addLog(
                when (stoppedMode) {
                    DesktopMode.LinuxTun -> "Desktop Linux TUN stopped"
                    DesktopMode.WindowsTun -> "Desktop Windows TUN stopped"
                    DesktopMode.SystemProxy -> "Desktop proxy stopped"
                    DesktopMode.LocalSocks -> "Desktop local SOCKS proxy stopped"
                    null -> "Desktop connection stopped"
                }
            )
        }
    }

    private fun cancelProcessJobs() {
        processWatchJob?.cancel()
        processWatchJob = null

        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = null

        logJob?.cancel()
        logJob = null

        tunLogJob?.cancel()
        tunLogJob = null
    }

    private fun startOlcRtcProcess(
        binary: Path,
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean,
        bindInterfaceIndex: Int?,
        dnsServer: String
    ): Process {
        val config = location.normalized()
        val provider = OlcRtcCommand.desktopProviderArg(config.bypassProvider)
        val olcRtcCommand = OlcRtcCommand(
            binary = binary,
            location = config,
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUser = socksSettings.username,
            socksPass = socksSettings.password,
            dnsServer = dnsServer
        )
        val configPath = writeOlcRtcClientConfig(olcRtcCommand)
        val command = olcRtcCommand.args(configPath)

        addLog("Starting olcRTC provider=$provider, transport=${config.transport}, room=${config.id}, port=${socksSettings.port}")

        if (privileged) {
            addLog("Linux TUN mode starts olcRTC with elevated privileges to bypass the TUN route")
        }

        val processBuilder = ProcessBuilder(
            if (privileged) LinuxPrivilege.command(command) else command
        ).redirectErrorStream(true)

        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"

        // Windows has no VpnService.protect. Pinning olcRTC's own sockets to the
        // physical interface is what keeps its provider calls off the TUN, so it
        // can still reach a conference to join a new room after the tunnel it
        // would otherwise have used is gone.
        if (bindInterfaceIndex != null) {
            processBuilder.environment()[OLCRTC_BIND_IFINDEX_ENV] = bindInterfaceIndex.toString()
            addLog("olcRTC sockets pinned to interface index $bindInterfaceIndex (keeps its own traffic off the TUN)")
        } else if (activeDesktopMode == DesktopMode.WindowsTun) {
            addLog("Could not determine the physical interface index - olcRTC traffic may follow the TUN route")
        }

        val startedProcess = try {
            processBuilder.start()
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(configPath) }
            if (olcRtcConfigPath == configPath) {
                olcRtcConfigPath = null
            }
            throw e
        }

        val readerJob = scope.launch {
            try {
                startedProcess.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        if (logOutput) {
                            val message = "rtc: $line"
                            addLog(message)
                            println(message)
                        }

                        if (line.contains("SOCKS5 server listening", ignoreCase = true)) {
                            ready.complete(Unit)
                        }

                        if (isSessionOpenedLine(line)) {
                            refreshRoomsAfterSwitch()
                        }

                        if (isFatalOlcRtcStartupLine(line)) {
                            startupFailure.complete(line)
                        }
                    }
                }
            } catch (_: IOException) {
                // Process stdout may close while stopping or after a remote disconnect.
            }
        }

        if (logOutput) {
            logJob?.cancel()
            logJob = readerJob
        }

        olcRtcBinary = binary

        return startedProcess
    }

    // Rewrites the running olcRTC config file with the current active location's
    // rooms. Only acts while connected and only for failover (multi-room)
    // locations, since single-room olcRTC does not reload. olcRTC picks the new
    // list up on its next hop - no process restart, live session untouched.
    private suspend fun rewriteActiveConfigIfConnected() {
        mutex.withLock {
            val path = olcRtcConfigPath ?: return
            val binary = olcRtcBinary ?: return

            val currentStatus = _status.value
            if (currentStatus !is VpnStatus.Connected &&
                currentStatus !is VpnStatus.Reconnecting
            ) {
                return
            }

            val active = locationsRepository.getActiveLocation()?.location?.normalized() ?: return

            val socksSettings = _socksProxySettings.value.normalized()
            val dnsServer = active.dnsServer.ifBlank { DesktopDnsResolver.current() }
            val command = OlcRtcCommand(
                binary = binary,
                location = active,
                socksHost = socksSettings.host,
                socksPort = socksSettings.port,
                socksUser = socksSettings.username,
                socksPass = socksSettings.password,
                dnsServer = dnsServer
            )

            runCatching {
                val tmp = Files.createTempFile(path.parent, "olcrtc-reload-", ".yaml")
                Files.writeString(tmp, command.yaml(), StandardCharsets.UTF_8)
                Files.move(
                    tmp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.onSuccess {
                // List the ids, not just the count: a rotation only works when the
                // standby the server advertised is actually in here, and a bare
                // count cannot tell "the standby is missing" from "there is one".
                val rooms = active.failoverRooms()
                addLog(
                    "olcRTC room list refreshed (${rooms.size} rooms: ${rooms.joinToString()}) " +
                        "- live reload, no restart"
                )
            }.onFailure {
                addLog("olcRTC live config reload failed: ${it.message}")
            }
        }
    }

    // olcRTC has just established a session - on a new room, if this followed a
    // handover. Pull the room list through the tunnel NOW rather than waiting for
    // the periodic refresh.
    //
    // Why it matters: the server retires the old room as soon as the client is
    // safely on the new one, so between a handover and the next scheduled fetch
    // the client's list holds exactly ONE live room. That window was minutes, and
    // handovers have been observed 95 seconds apart - closer together than the
    // refresh interval, which no amount of waiting can survive. On a whitelist a
    // client with no live room left cannot ask for a new one: the request would
    // have to travel through the tunnel it just lost.
    private fun refreshRoomsAfterSwitch() {
        // Only meaningful once the tunnel carries traffic: the fetch rides its
        // SOCKS. The first session of a connection lands here while we are still
        // Connecting, and is covered by the refresh on the Connected transition.
        if (_status.value !is VpnStatus.Connected) return

        val now = System.currentTimeMillis()
        if (now - lastSwitchRefreshAt < SWITCH_REFRESH_MIN_INTERVAL_MS) return
        lastSwitchRefreshAt = now

        scope.launch {
            runCatching {
                locationsRepository.refreshSubscriptions(
                    subscriptionProxy = subscriptionFetchProxy()
                )
            }.onFailure {
                // Nothing to recover here - the periodic refresh is still running,
                // and the rotation gate holds the next handover until the client
                // has actually received the pair.
                addLog("Room list refresh after handover failed: ${it.message}")
            }
        }
    }

    private fun writeOlcRtcClientConfig(command: OlcRtcCommand): Path {
        val runtimeDir = DesktopPaths.appDataDir().resolve("runtime")
        Files.createDirectories(runtimeDir)
        val path = Files.createTempFile(runtimeDir, "olcrtc-client-", ".yaml")
        Files.writeString(path, command.yaml(), StandardCharsets.UTF_8)
        deleteOlcRtcConfig()
        olcRtcConfigPath = path
        return path
    }

    private fun deleteOlcRtcConfig() {
        olcRtcConfigPath?.let { path ->
            runCatching { Files.deleteIfExists(path) }
        }
        olcRtcConfigPath = null
    }

    private fun startTunLogReader(target: Process) {
        tunLogJob?.cancel()

        tunLogJob = scope.launch {
            try {
                target.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        val message = "tun: $line"
                        addLog(message)
                        println(message)
                    }
                }
            } catch (_: IOException) {
                // TUN stdout may close while the process is being stopped.
            }
        }
    }

    private fun startProcessExitWatchers(
        desktopMode: DesktopMode,
        olcRtcProcess: Process,
        currentTunProcess: Process?,
        requestGeneration: Long
    ) {
        startOlcRtcExitWatcher(olcRtcProcess, requestGeneration)

        when (desktopMode) {
            DesktopMode.LinuxTun,
            DesktopMode.WindowsTun -> startTunExitWatcher(
                currentTunProcess ?: error("TUN process is missing"),
                requestGeneration
            )
            DesktopMode.SystemProxy,
            DesktopMode.LocalSocks -> {
                tunProcessWatchJob?.cancel()
                tunProcessWatchJob = null
            }
        }
    }

    private fun startOlcRtcExitWatcher(target: Process, requestGeneration: Long) {
        processWatchJob?.cancel()
        processWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || process !== target) return@withLock

                    handleUnexpectedProcessExit(
                        logMessage = "olcRTC process exited unexpectedly with code $exitCode",
                        errorMessage = "olcRTC exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    private fun startTunExitWatcher(target: Process, requestGeneration: Long) {
        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || tunProcess !== target) return@withLock

                    handleUnexpectedProcessExit(
                        logMessage = "TUN process exited unexpectedly with code $exitCode",
                        errorMessage = "TUN process exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    private suspend fun handleUnexpectedProcessExit(
        logMessage: String,
        errorMessage: String,
        requestGeneration: Long
    ) {
        addLog(logMessage)
        stopDesktopMode(finalStatus = false)

        // A user start/stop bumps `generation` up front; if that already happened,
        // this dead connection is superseded and we must not touch anything.
        if (requestGeneration != generation) return

        // Auto-reconnect. On a whitelist we cannot fetch a fresh subscription without
        // a live tunnel, so we simply relaunch onto the STORED active location (kept
        // current by the background refresh while a tunnel was up). No network here.
        //
        // This retries indefinitely, on purpose. Giving up used to leave the app in
        // Error after about two minutes, which is shorter than a laptop waking, a
        // Wi-Fi roam or a provider hiccup - and on a whitelist there is no other way
        // back: reconnecting IS the only path to a working tunnel, and a working
        // tunnel is the only path to a fresh room list. A capped backoff makes an
        // endless retry cheap; the user can still stop it, which bumps `generation`
        // and drops us out on the next check.
        addLog("Connection lost ($errorMessage) - reconnecting until it comes back")

        var expectedGeneration = requestGeneration
        var attempt = 0
        while (true) {
            attempt++
            setStatus(VpnStatus.Reconnecting)
            val backoff = reconnectBackoffMs(attempt)
            if (attempt <= RECONNECT_VERBOSE_ATTEMPTS || attempt % RECONNECT_LOG_EVERY == 0) {
                addLog("Auto-reconnect attempt $attempt in ${backoff / 1000}s")
            }

            reconnectBackoffDelay(backoff, expectedGeneration)
            if (generation != expectedGeneration) return  // a user action superseded us

            val attemptGeneration = ++generation
            expectedGeneration = attemptGeneration
            startDesktopMode(attemptGeneration, isRestart = true)

            if (generation != attemptGeneration) return   // user acted during the attempt
            if (_status.value is VpnStatus.Connected) {
                addLog("Auto-reconnect succeeded on attempt $attempt")
                return
            }
            // attempt failed with no user action -> loop and retry
        }
    }

    /** Interruptible backoff: bails early the moment a user action bumps generation. */
    private suspend fun reconnectBackoffDelay(totalMs: Long, expectedGeneration: Long) {
        var waited = 0L
        while (waited < totalMs) {
            if (generation != expectedGeneration) return
            val step = minOf(RECONNECT_BACKOFF_POLL_MS, totalMs - waited)
            delay(step)
            waited += step
        }
    }

    private fun reconnectBackoffMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 4)
        return (RECONNECT_BASE_BACKOFF_MS * (1L shl shift))
            .coerceAtMost(RECONNECT_MAX_BACKOFF_MS)
    }

    private fun waitForProcessExit(target: Process): Int? {
        return try {
            target.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private suspend fun waitForOlcRtcReady(
        process: Process,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        socksPort: Int,
        requestGeneration: Long? = null
    ) {
        val deadline = System.currentTimeMillis() + OLC_READY_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("olcRTC failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (ready.isCompleted || canConnectToSocks(socksPort)) {
                waitForOlcRtcStartupStability(process, startupFailure, requestGeneration)
                return
            }

            if (!process.isAlive) {
                error("olcRTC exited before SOCKS5 was ready")
            }

            delay(READY_POLL_INTERVAL_MS)
        }

        error("olcRTC start timed out")
    }

    private suspend fun waitForOlcRtcStartupStability(
        process: Process,
        startupFailure: CompletableDeferred<String>,
        requestGeneration: Long?
    ) {
        val deadline = System.currentTimeMillis() + OLC_STARTUP_STABILITY_MS
        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("olcRTC failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (!process.isAlive) {
                error("olcRTC exited before desktop proxy was enabled")
            }

            delay(READY_POLL_INTERVAL_MS)
        }
    }

    private fun canConnectToSocks(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, port),
                    TCP_CONNECT_TIMEOUT_MS.toInt()
                )
            }
        }.isSuccess
    }

    private fun stopProcess(target: Process?) {
        if (target == null) return
        if (!target.isAlive) return

        target.toHandle().descendants().forEach {
            it.destroy()
        }

        target.destroy()

        if (!target.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            target.toHandle().descendants().forEach {
                it.destroyForcibly()
            }

            target.destroyForcibly()
            target.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    private fun addLog(message: String) {
        _logs.update {
            (it + message).takeLast(MAX_LOG_ENTRIES)
        }
    }

    private companion object {
        const val MAX_LOG_ENTRIES = 5_000
        const val OLC_READY_TIMEOUT_MS = 25_000L
        const val OLC_STARTUP_STABILITY_MS = 1_500L
        const val READY_POLL_INTERVAL_MS = 200L
        const val TCP_CONNECT_TIMEOUT_MS = 250L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        const val DEFAULT_LOCATION_PING_PARALLELISM = 4

        // Read by olcRTC's protect package; must match protect.BindInterfaceEnv.
        const val OLCRTC_BIND_IFINDEX_ENV = "OLCRTC_BIND_IFINDEX"

        // Desktop auto-reconnect on unexpected olcRTC/TUN exit (whitelist-safe:
        // relaunch onto the stored active location, no network fetch).
        // Auto-reconnect never gives up (see reconnect loop), so the attempt log
        // is throttled: every attempt at first, then occasionally, so a long
        // outage leaves a trail without burying everything else in the log.
        const val RECONNECT_VERBOSE_ATTEMPTS = 6
        const val RECONNECT_LOG_EVERY = 20
        const val RECONNECT_BASE_BACKOFF_MS = 2_000L
        const val RECONNECT_MAX_BACKOFF_MS = 20_000L
        const val RECONNECT_BACKOFF_POLL_MS = 250L

        const val SWITCH_REFRESH_MIN_INTERVAL_MS = 15_000L

        internal fun isFatalOlcRtcStartupLine(line: String): Boolean {
            val text = line.lowercase()
            return "failed to connect link" in text ||
                    "join room failed" in text ||
                    "get room token" in text && "failed" in text ||
                    "transport connect" in text && "failed" in text
        }
    }
}

/**
 * olcRTC logs this once a tunnel session is established - including the new
 * session it opens after a room handover, which is the moment the client can
 * safely go and fetch the room list through the tunnel again.
 *
 * Top-level rather than in the companion so it stays reachable from tests
 * without widening the visibility of everything else in there.
 */
internal fun isSessionOpenedLine(line: String): Boolean {
    val text = line.lowercase()
    return "session " in text && " opened (device=" in text
}
