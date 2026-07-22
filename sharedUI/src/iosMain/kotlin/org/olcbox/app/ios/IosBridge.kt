package org.olcbox.app.ios

data class IosOlcRtcStartRequest(
    val carrierName: String,
    val transportName: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPass: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int
)

data class IosOlcRtcCheckRequest(
    val carrierName: String,
    val transportName: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val timeoutMillis: Long,
    val pingUrl: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int
)

data class IosBridgeResult(
    val success: Boolean,
    val message: String?
)

data class IosLongResult(
    val success: Boolean,
    val valueMillis: Long,
    val message: String?
)

interface IosLogWriter {
    fun writeLog(message: String)
}

interface IosTextCallback {
    fun onSuccess(text: String)
    fun onError(message: String)
}

interface IosMessageCallback {
    fun onSuccess(message: String)
    fun onError(message: String)
}

interface IosOlcRtcBridge {
    fun setLogWriter(writer: IosLogWriter?)
    fun start(request: IosOlcRtcStartRequest): IosBridgeResult
    fun stop()
    fun isRunning(): Boolean
    fun ping(request: IosOlcRtcCheckRequest): IosLongResult
    fun check(request: IosOlcRtcCheckRequest): IosLongResult
}

/**
 * Start parameters for the packet-tunnel (VPN) mode. Mirrors [IosOlcRtcStartRequest]
 * plus the extra fields the Network Extension needs to build its utun/hev config.
 */
data class IosTunnelStartRequest(
    val carrierName: String,
    val transportName: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPass: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int,
    val mtu: Int,
    val dnsAddress: String
)

interface IosTunnelStatusListener {
    /**
     * @param status one of: disconnected, connecting, connected, reconnecting,
     *               disconnecting, invalid.
     * @param message optional human-readable detail (e.g. an error).
     */
    fun onStatus(status: String, message: String?)
}

/**
 * Swift-implemented control surface over `NETunnelProviderManager` for TUN mode.
 * The extension itself hosts the olcRTC engine + hev-socks5-tunnel; this bridge only
 * installs/loads the VPN profile and starts/stops it.
 */
interface IosTunnelBridge {
    fun setStatusListener(listener: IosTunnelStatusListener?)

    /** True if a saved VPN profile already exists (no permission prompt needed). */
    fun isConfigured(): Boolean

    /** True if the tunnel is currently connected or connecting. */
    fun isActive(): Boolean

    /**
     * Installs (prompting for VPN permission the first time) and starts the tunnel.
     * Reports the terminal outcome of the start attempt via [callback].
     */
    fun start(request: IosTunnelStartRequest, callback: IosMessageCallback)

    fun stop()
}

interface IosPlatformBridge {
    fun readClipboard(): String?
    fun writeClipboard(text: String)
    fun pickConfigText(callback: IosTextCallback)
    fun shareText(title: String, text: String)
    fun saveLogs(defaultName: String, content: String, callback: IosMessageCallback)
    fun shareLogs(defaultName: String, content: String, callback: IosMessageCallback)
    fun showMessage(message: String)
}
