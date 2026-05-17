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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.DesktopProxyController
import org.olcbox.app.vpn.desktop.LinuxPrivilege
import org.olcbox.app.vpn.desktop.LinuxTunController
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import org.olcbox.app.vpn.desktop.PacServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.file.Path
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
    private var watchdogJob: Job? = null
    private var process: Process? = null
    private var tunProcess: Process? = null
    private var generation = 0L
    private var recoveryRequestedForGeneration = 0L
    private val recoveryState = DesktopRtcRecoveryState(
        startupGraceMs = RTC_RECOVERY_GRACE_MS,
        failureWindowMs = RTC_FAILURE_WINDOW_MS,
        trafficProbeThreshold = TRAFFIC_PROBE_FAILURE_THRESHOLD
    )
    private val linuxTunController = LinuxTunController(::addLog)

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
        return OlcRtcConnectionChecker.ping(locationConfig)
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.check(locationConfig)
    }

    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        val settings = DesktopSocksProxySettings(
            port = port,
            username = username,
            password = password
        ).normalized()
        _socksProxySettings.value = settings
        pacServer.updateSocksTarget(settings.host, settings.port)
    }

    fun updateSocksProxySettings(settings: DesktopSocksProxySettings) {
        val normalized = settings.normalized()
        _socksProxySettings.value = normalized
        pacServer.updateSocksTarget(normalized.host, normalized.port)
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
            val useLinuxTun = DesktopPaths.os == DesktopOs.Linux
            val socksSettings = _socksProxySettings.value.normalized()

            process = startOlcRtcProcessWithFallback(
                location = location,
                socksSettings = socksSettings,
                ready = ready,
                logOutput = true,
                privileged = useLinuxTun,
                requestGeneration = requestGeneration
            )

            waitForOlcRtcReady(
                process = process ?: error("olcRTC process is missing"),
                ready = ready,
                socksPort = socksSettings.port,
                requestGeneration = requestGeneration
            )

            if (requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (useLinuxTun) {
                val hevBinary = DesktopNativeAssets.resolveHevSocks5TunnelBinary()

                tunProcess = linuxTunController.start(hevBinary, socksSettings.port)

                if (requestGeneration != generation) {
                    throw CancellationException("Desktop start superseded")
                }

                startTunLogReader(tunProcess ?: error("hev-socks5-tunnel process is missing"))
            } else {
                pacServer.start(socksSettings.host, socksSettings.port)
                proxyController.enable(
                    pacUrl = pacServer.url,
                    socksHost = socksSettings.host,
                    socksPort = socksSettings.port
                )

                if (requestGeneration != generation) {
                    throw CancellationException("Desktop start superseded")
                }
            }

            recoveryRequestedForGeneration = 0L
            recoveryState.markConnected()
            setStatus(VpnStatus.Connected)
            startWatchdog(requestGeneration, socksSettings.port)
            addLog(if (useLinuxTun) "Desktop Linux TUN connected" else "Desktop proxy connected")
        } catch (e: Exception) {
            if (e is CancellationException) {
                addLog("Desktop start cancelled")
            } else {
                addLog("Desktop start failed: ${e.message}")
            }

            if (DesktopPaths.os == DesktopOs.Linux) {
                runCatching {
                    linuxTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Linux TUN cleanup failed: ${it.message}")
                }

                tunProcess = null
            } else {
                runCatching {
                    proxyController.restore()
                }.onFailure {
                    addLog("Proxy restore failed: ${it.message}")
                }
            }

            pacServer.stop()
            stopProcess(process)
            process = null

            if (e !is CancellationException && requestGeneration == generation) {
                if (isRestart) {
                    setStatus(VpnStatus.Reconnecting)
                    scheduleDesktopRetry(
                        requestGeneration = requestGeneration,
                        reason = e.message ?: "desktop start failed"
                    )
                } else {
                    setStatus(VpnStatus.Error(e.message ?: "Desktop start failed"))
                }
            }
        }
    }

    private fun startOlcRtcProcessWithFallback(
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        logOutput: Boolean,
        privileged: Boolean,
        requestGeneration: Long
    ): Process {
        val binaries = DesktopNativeAssets.resolveOlcRtcBinaryCandidates()
        var lastException: Exception? = null

        for (binary in binaries) {
            try {
                return startOlcRtcProcess(
                    binary = binary,
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    logOutput = logOutput,
                    privileged = privileged,
                    requestGeneration = requestGeneration
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
            return
        }

        setStatus(VpnStatus.Stopping)

        if (DesktopPaths.os == DesktopOs.Linux) {
            runCatching {
                linuxTunController.stop(tunProcess)
            }.onFailure {
                addLog("Linux TUN stop failed: ${it.message}")
            }

            tunProcess = null
        } else {
            runCatching {
                proxyController.restore()
            }.onFailure {
                addLog("Proxy restore failed: ${it.message}")
            }
        }

        pacServer.stop()

        watchdogJob?.cancel()
        watchdogJob = null

        stopProcess(process)
        process = null

        logJob?.cancel()
        logJob = null

        tunLogJob?.cancel()
        tunLogJob = null

        if (finalStatus) {
            setStatus(VpnStatus.Disconnected)
            addLog(if (DesktopPaths.os == DesktopOs.Linux) "Desktop Linux TUN stopped" else "Desktop proxy stopped")
        }
    }

    private fun startOlcRtcProcess(
        binary: Path,
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        logOutput: Boolean,
        privileged: Boolean,
        requestGeneration: Long
    ): Process {
        val config = location.normalized()
        val provider = OlcRtcCommand.desktopProviderArg(config.bypassProvider)
        val dataDir = DesktopNativeAssets.resolveOlcRtcDataDir()
        val configFile = DesktopPaths.appDataDir().resolve("olcrtc-client.yaml")
        val command = OlcRtcCommand(
            binary = binary,
            location = config,
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUser = socksSettings.username,
            socksPass = socksSettings.password,
            dataDir = dataDir,
            configFile = configFile
        )
        command.writeConfigFile()
        val args = command.args()

        addLog("Starting olcRTC carrier=$provider, transport=${config.transport}, room=${config.id}, port=${socksSettings.port}")

        if (privileged) {
            addLog("Linux TUN mode starts olcRTC with elevated privileges to bypass the TUN route")
        }

        val processBuilder = ProcessBuilder(
            if (privileged) LinuxPrivilege.command(args) else args
        ).redirectErrorStream(true)

        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"

        val startedProcess = processBuilder.start()

        val readerJob = scope.launch {
            startedProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!isActive) return@forEach

                    if (logOutput) {
                        addLog("rtc: $line")
                    }

                    handleRtcLine(line, requestGeneration = requestGeneration)

                    if (line.contains("SOCKS5 server listening", ignoreCase = true)) {
                        ready.complete(Unit)
                    }
                }
            }
        }

        if (logOutput) {
            logJob?.cancel()
            logJob = readerJob
        }

        return startedProcess
    }

    private fun startTunLogReader(target: Process) {
        tunLogJob?.cancel()

        tunLogJob = scope.launch {
            target.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!isActive) return@forEach

                    addLog("tun: $line")
                }
            }
        }
    }

    private fun startWatchdog(requestGeneration: Long, socksPort: Int) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && requestGeneration == generation && _status.value is VpnStatus.Connected) {
                delay(WATCHDOG_INTERVAL_MS)

                if (requestGeneration != generation || _status.value !is VpnStatus.Connected) return@launch

                if (process?.isAlive != true) {
                    requestDesktopRecovery(
                        reason = "olcRTC process stopped",
                        fullRestart = false,
                        requestGeneration = requestGeneration
                    )
                    return@launch
                }

                if (!canConnectToSocks(socksPort)) {
                    requestDesktopRecovery(
                        reason = "SOCKS port unavailable",
                        fullRestart = true,
                        requestGeneration = requestGeneration
                    )
                    return@launch
                }

                recoveryState.noteTrafficProbe(success = httpProbeThroughSocks(socksPort))?.let { request ->
                    requestDesktopRecovery(
                        reason = request.reason,
                        fullRestart = request.fullRestart,
                        requestGeneration = requestGeneration
                    )
                    return@launch
                }
            }
        }
    }

    private fun handleRtcLine(line: String, requestGeneration: Long) {
        when (val event = RtcLogRecoveryClassifier.classify(line)) {
            RtcLogEvent.Connected -> recoveryState.markConnected()
            is RtcLogEvent.Failure -> {
                recoveryState.noteRtcFailure(event)?.let { request ->
                    requestDesktopRecovery(
                        reason = request.reason,
                        fullRestart = request.fullRestart,
                        requestGeneration = requestGeneration
                    )
                }
            }
            null -> Unit
        }
    }

    private fun requestDesktopRecovery(
        reason: String,
        fullRestart: Boolean,
        requestGeneration: Long
    ) {
        if (requestGeneration != generation) return
        if (_status.value !is VpnStatus.Connected && _status.value !is VpnStatus.Reconnecting) return
        if (recoveryRequestedForGeneration == requestGeneration) return

        recoveryRequestedForGeneration = requestGeneration
        addLog("Watchdog: $reason; reconnecting${if (fullRestart) " with full restart" else ""}")

        scope.launch {
            mutex.withLock {
                if (requestGeneration != generation) return@withLock
                setStatus(VpnStatus.Reconnecting)
                stopDesktopMode(finalStatus = false)
                if (requestGeneration == generation) {
                    startDesktopMode(requestGeneration, isRestart = true)
                }
            }
        }
    }

    private fun scheduleDesktopRetry(requestGeneration: Long, reason: String) {
        scope.launch {
            delay(RECONNECT_RETRY_DELAY_MS)
            if (requestGeneration != generation) return@launch
            if (_status.value !is VpnStatus.Reconnecting) return@launch

            addLog("Retrying desktop transport after $reason")

            mutex.withLock {
                if (requestGeneration != generation) return@withLock
                if (_status.value !is VpnStatus.Reconnecting) return@withLock

                startDesktopMode(requestGeneration, isRestart = true)
            }
        }
    }

    private fun httpProbeThroughSocks(socksPort: Int): Boolean {
        return HTTP_PROBE_URLS.any { url ->
            runCatching {
                val proxy = Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, socksPort)
                )
                val connection = URL(url).openConnection(proxy) as HttpURLConnection

                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = HTTP_PROBE_TIMEOUT_MS.toInt()
                    connection.readTimeout = HTTP_PROBE_TIMEOUT_MS.toInt()
                    connection.requestMethod = "GET"
                    connection.responseCode in 200..399
                } finally {
                    connection.disconnect()
                }
            }.isSuccess
        }
    }

    private suspend fun waitForOlcRtcReady(
        process: Process,
        ready: CompletableDeferred<Unit>,
        socksPort: Int,
        requestGeneration: Long? = null
    ) {
        val deadline = System.currentTimeMillis() + OLC_READY_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (ready.isCompleted || canConnectToSocks(socksPort)) {
                return
            }

            if (!process.isAlive) {
                error("olcRTC exited before SOCKS5 was ready")
            }

            delay(READY_POLL_INTERVAL_MS)
        }

        error("olcRTC start timed out")
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
        const val READY_POLL_INTERVAL_MS = 200L
        const val TCP_CONNECT_TIMEOUT_MS = 250L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        const val DEFAULT_LOCATION_PING_PARALLELISM = 4
        const val WATCHDOG_INTERVAL_MS = 30_000L
        const val HTTP_PROBE_TIMEOUT_MS = 8_000L
        const val RECONNECT_RETRY_DELAY_MS = 3_000L
        const val RTC_RECOVERY_GRACE_MS = 2_500L
        const val RTC_FAILURE_WINDOW_MS = 6_000L
        const val TRAFFIC_PROBE_FAILURE_THRESHOLD = 2
        val HTTP_PROBE_URLS = listOf(
            "https://www.google.com/generate_204",
            "https://cloudflare.com/cdn-cgi/trace"
        )
    }
}
