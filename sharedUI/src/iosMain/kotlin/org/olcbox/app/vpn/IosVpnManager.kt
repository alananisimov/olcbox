package org.olcbox.app.vpn

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.ios.IosBridgeResult
import org.olcbox.app.ios.IosLogWriter
import org.olcbox.app.ios.IosOlcRtcBridge
import org.olcbox.app.ios.IosOlcRtcCheckRequest
import org.olcbox.app.ios.IosOlcRtcStartRequest
import org.olcbox.app.ui.components.ApplicationSocksProxySettings
import platform.Foundation.NSUserDefaults

class IosVpnManager(
    private val locationsRepository: LocationsRepository,
    private val olcRtcBridge: IosOlcRtcBridge
) : VpnManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _socksProxySettings = MutableStateFlow(loadSocksProxySettings())
    val socksProxySettings: StateFlow<ApplicationSocksProxySettings> = _socksProxySettings.asStateFlow()

    private var operationJob: Job? = null
    private var generation = 0L

    // Auto-reconnect state. The iOS transport (Go/WebRTC) does not restart ICE on
    // its own, so when the underlying connection drops (network migration, the app
    // being briefly suspended, TURN failures) we detect it and rebuild the SOCKS
    // session ourselves — mirroring what OlcboxVpnService does on Android.
    private var desiredConnected = false
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val timeSource = TimeSource.Monotonic
    private var lastReadyMark: TimeSource.Monotonic.ValueTimeMark? = null

    init {
        olcRtcBridge.setLogWriter(object : IosLogWriter {
            override fun writeLog(message: String) {
                message
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        addLog("rtc: $it")
                        handleRtcLine(it)
                    }
            }
        })
    }

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        desiredConnected = true
        reconnectAttempt = 0
        reconnectJob?.cancel()
        val requestedGeneration = ++generation
        operationJob = scope.launch {
            mutex.withLock {
                if (requestedGeneration != generation) return@withLock

                val shouldRestart = _status.value is VpnStatus.Connected ||
                    _status.value is VpnStatus.Connecting ||
                    _status.value is VpnStatus.Reconnecting ||
                    olcRtcBridge.isRunning()

                if (shouldRestart) {
                    setStatus(VpnStatus.Reconnecting)
                    addLog("Restarting iOS SOCKS connection")
                    stopOlcRtc()
                    if (requestedGeneration != generation) return@withLock
                }

                startOlcRtc(requestedGeneration, isRestart = shouldRestart)
            }
        }
    }

    override fun stopVpn() {
        desiredConnected = false
        watchdogJob?.cancel()
        reconnectJob?.cancel()
        generation++
        operationJob = scope.launch {
            mutex.withLock {
                setStatus(VpnStatus.Stopping)
                stopOlcRtc()
                setStatus(VpnStatus.Disconnected)
                addLog("iOS SOCKS stopped")
            }
        }
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        return runCheck(locationConfig) { request -> olcRtcBridge.ping(request) }
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return runCheck(locationConfig) { request -> olcRtcBridge.check(request) }
    }

    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        val settings = ApplicationSocksProxySettings(
            port = sanitizePort(port),
            username = username.trim().take(MAX_CREDENTIAL_LENGTH).ifBlank { generateCredential(USERNAME_LENGTH) },
            password = password.trim().take(MAX_CREDENTIAL_LENGTH).ifBlank { generateCredential(PASSWORD_LENGTH) }
        )
        _socksProxySettings.value = settings
        saveSocksProxySettings(settings)
    }

    fun regenerateSocksProxyPassword() {
        val current = _socksProxySettings.value
        updateSocksProxySettings(
            username = current.username,
            password = generateCredential(PASSWORD_LENGTH),
            port = current.port
        )
    }

    fun close() {
        desiredConnected = false
        watchdogJob?.cancel()
        reconnectJob?.cancel()
        generation++
        runCatching { olcRtcBridge.setLogWriter(null) }
        runCatching { olcRtcBridge.stop() }
        scope.cancel()
    }

    private suspend fun startOlcRtc(requestedGeneration: Long, isRestart: Boolean) {
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)

        val active = locationsRepository.getActiveLocation()
        val location = active?.location?.normalized()

        if (location == null || !location.isComplete()) {
            setStatus(VpnStatus.Error("No active location"))
            addLog("Add a valid location before starting iOS SOCKS")
            return
        }

        val deviceId = locationsRepository.getDeviceIdentity()
        val socksSettings = _socksProxySettings.value
        val request = location.startRequest(deviceId, socksSettings)

        addLog(
            "Starting iOS SOCKS provider=${location.bypassProvider}, " +
                "transport=${location.transport}, room=${location.id}, port=${socksSettings.port}"
        )

        val result = withContext(Dispatchers.Default) {
            olcRtcBridge.start(request)
        }

        if (requestedGeneration != generation) return

        if (result.success) {
            setStatus(VpnStatus.Connected)
            addLog("iOS SOCKS ready on 127.0.0.1:${socksSettings.port}")
            reconnectAttempt = 0
            lastReadyMark = timeSource.markNow()
            startWatchdog()
        } else {
            val message = result.message ?: "olcRTC start failed"
            setStatus(VpnStatus.Error(message))
            addLog("iOS SOCKS start failed: $message")
            stopOlcRtc()
        }
    }

    private suspend fun runCheck(
        locationConfig: LocationConfig,
        block: (IosOlcRtcCheckRequest) -> org.olcbox.app.ios.IosLongResult
    ): Long? = withContext(Dispatchers.Default) {
        val config = locationConfig.normalized()
        if (!config.isComplete()) return@withContext null
        val request = IosOlcRtcCheckRequest(
            carrierName = config.bypassProvider,
            transportName = config.transport,
            roomId = config.id,
            clientId = locationsRepository.getDeviceIdentity(),
            keyHex = config.key,
            timeoutMillis = CHECK_TIMEOUT_MS,
            pingUrl = HTTP_PING_URL,
            vp8Fps = config.vp8Fps,
            vp8BatchSize = config.vp8Batch
        )
        val result = block(request)
        if (result.success && result.valueMillis >= 0L) result.valueMillis else null
    }

    private fun stopOlcRtc(): IosBridgeResult {
        return runCatching {
            olcRtcBridge.stop()
            IosBridgeResult(success = true, message = null)
        }.getOrElse {
            IosBridgeResult(success = false, message = it.message)
        }
    }

    /**
     * Parses olcRTC log lines to detect transport health. The native layer never
     * performs an ICE restart, so a dropped connection stays dead until we rebuild
     * it. We treat "connected/listening" markers as healthy and "failed/closed/
     * broken pipe" markers as a lost transport that must be reconnected.
     */
    private fun handleRtcLine(line: String) {
        if (!desiredConnected) return
        val lower = line.lowercase()

        if (lower.contains("socks5 server listening") ||
            lower.contains("ice connection state changed: connected") ||
            lower.contains("peer connection state changed: connected")
        ) {
            reconnectAttempt = 0
            lastReadyMark = timeSource.markNow()
            return
        }

        val transportLost = lower.contains("ice connection state changed: failed") ||
            lower.contains("peer connection state changed: failed") ||
            lower.contains("ice connection state changed: closed") ||
            lower.contains("peer connection state changed: closed") ||
            lower.contains("read/write on closed pipe") ||
            lower.contains("use of closed network connection")

        if (transportLost) {
            // Ignore teardown noise that immediately follows a fresh (re)connect.
            val recentlyReady = lastReadyMark
                ?.elapsedNow()
                ?.inWholeMilliseconds
                ?.let { it < POST_CONNECT_GRACE_MS }
                ?: false
            if (recentlyReady) return
            scheduleReconnect("RTC transport lost")
        }
    }

    /**
     * Periodically verifies the SOCKS transport is still up while the user wants to
     * stay connected, catching silent deaths that produce no log marker.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && desiredConnected) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!desiredConnected) break
                val stalled = _status.value is VpnStatus.Connected &&
                    reconnectJob?.isActive != true &&
                    !olcRtcBridge.isRunning()
                if (stalled) {
                    addLog("Watchdog: iOS SOCKS transport is down")
                    scheduleReconnect("transport stopped")
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!desiredConnected) return
        if (reconnectJob?.isActive == true) return
        val status = _status.value
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) return

        reconnectJob = scope.launch {
            setStatus(VpnStatus.Reconnecting)
            addLog("Auto-reconnect requested ($reason)")

            // Keep retrying with exponential backoff until we reconnect or the user
            // turns the connection off. A single failed attempt (e.g. no network yet)
            // must not give up — that is what left the transport dead before.
            while (desiredConnected && isActive) {
                val delayMs = nextReconnectDelay()
                addLog("Reconnecting iOS SOCKS in ${delayMs / 1000}s")
                delay(delayMs)
                if (!desiredConnected) return@launch

                val requestedGeneration = ++generation
                val reconnected = mutex.withLock {
                    if (requestedGeneration != generation || !desiredConnected) return@withLock false
                    stopOlcRtc()
                    startOlcRtc(requestedGeneration, isRestart = true)
                    _status.value is VpnStatus.Connected
                }

                if (reconnected || !desiredConnected) return@launch
                // startOlcRtc reports failure via Error status; keep the user-facing
                // state as Reconnecting so the retry loop stays coherent.
                if (_status.value !is VpnStatus.Reconnecting) setStatus(VpnStatus.Reconnecting)
            }
        }
    }

    private fun nextReconnectDelay(): Long {
        val multiplier = 1L shl reconnectAttempt.coerceAtMost(MAX_RECONNECT_BACKOFF_POWER)
        reconnectAttempt++
        return (RECONNECT_BASE_DELAY_MS * multiplier).coerceAtMost(RECONNECT_MAX_DELAY_MS)
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    private fun addLog(message: String) {
        _logs.value = (_logs.value + message).takeLast(MAX_LOG_LINES)
    }

    private fun LocationConfig.startRequest(
        deviceId: String,
        settings: ApplicationSocksProxySettings
    ): IosOlcRtcStartRequest {
        val config = normalized()
        return IosOlcRtcStartRequest(
            carrierName = config.bypassProvider,
            transportName = config.transport,
            roomId = config.id,
            clientId = deviceId,
            keyHex = config.key,
            socksPort = settings.port,
            socksUser = settings.username,
            socksPass = settings.password,
            vp8Fps = config.vp8Fps,
            vp8BatchSize = config.vp8Batch
        )
    }

    private fun loadSocksProxySettings(): ApplicationSocksProxySettings {
        val defaults = NSUserDefaults.standardUserDefaults
        val port = sanitizePort(defaults.integerForKey(KEY_SOCKS_PORT).toInt())
        val username = defaults.stringForKey(KEY_SOCKS_USERNAME)
            ?.takeIf { it.isNotBlank() }
            ?: generateCredential(USERNAME_LENGTH)
        val password = defaults.stringForKey(KEY_SOCKS_PASSWORD)
            ?.takeIf { it.isNotBlank() }
            ?: generateCredential(PASSWORD_LENGTH)
        return ApplicationSocksProxySettings(
            port = port,
            username = username,
            password = password
        ).also { saveSocksProxySettings(it) }
    }

    private fun saveSocksProxySettings(settings: ApplicationSocksProxySettings) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setInteger(settings.port.toLong(), KEY_SOCKS_PORT)
        defaults.setObject(settings.username, KEY_SOCKS_USERNAME)
        defaults.setObject(settings.password, KEY_SOCKS_PASSWORD)
    }

    private fun sanitizePort(port: Int): Int {
        return if (ApplicationSocksProxySettings.isValidPort(port)) {
            port
        } else {
            ApplicationSocksProxySettings.DEFAULT_PORT
        }
    }

    private fun generateCredential(length: Int): String {
        val boundedLength = min(max(length, 1), MAX_CREDENTIAL_LENGTH)
        return buildString(boundedLength) {
            repeat(boundedLength) {
                append(CREDENTIAL_ALPHABET[Random.nextInt(CREDENTIAL_ALPHABET.length)])
            }
        }
    }

    private companion object {
        const val KEY_SOCKS_PORT = "ios_socks_port"
        const val KEY_SOCKS_USERNAME = "ios_socks_username"
        const val KEY_SOCKS_PASSWORD = "ios_socks_password"
        const val USERNAME_LENGTH = 12
        const val PASSWORD_LENGTH = 24
        const val MAX_CREDENTIAL_LENGTH = 64
        const val MAX_LOG_LINES = 500
        const val CHECK_TIMEOUT_MS = 8_000L
        const val HTTP_PING_URL = "https://www.google.com/generate_204"
        const val WATCHDOG_INTERVAL_MS = 10_000L
        const val RECONNECT_BASE_DELAY_MS = 2_000L
        const val RECONNECT_MAX_DELAY_MS = 30_000L
        const val MAX_RECONNECT_BACKOFF_POWER = 3
        const val POST_CONNECT_GRACE_MS = 4_000L
        const val CREDENTIAL_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    }
}
