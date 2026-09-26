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
    val dnsServer: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int
)

data class IosOlcRtcCheckRequest(
    val carrierName: String,
    val transportName: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val dnsServer: String,
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

interface IosPlatformBridge {
    fun readClipboard(): String?
    fun writeClipboard(text: String)
    fun pickConfigText(callback: IosTextCallback)
    fun shareText(title: String, text: String)
    fun saveLogs(defaultName: String, content: String, callback: IosMessageCallback)
    fun shareLogs(defaultName: String, content: String, callback: IosMessageCallback)
    fun showMessage(message: String)
}
