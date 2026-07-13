package org.olcbox.app.vpn.desktop.daemon

import co.touchlab.kermit.Logger
import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.olcbox.daemon.ipc.HmacHeaders
import org.olcbox.daemon.ipc.HmacSigner
import org.olcbox.daemon.ipc.IpcSecret
import org.olcbox.daemon.ipc.LogsResponse
import org.olcbox.daemon.ipc.StartTunnelRequest
import org.olcbox.daemon.ipc.TunnelStatusResponse

// Thrown specifically when the daemon's Unix socket can't be reached at all
// (not installed, or the systemd service isn't running) — callers use this
// to distinguish "go run the install command" from a genuine tunnel error.
internal class DaemonUnavailableException(cause: Throwable) :
    Exception("olcbox-daemon is not installed or not running", cause)

// Hand-rolled HTTP/1.1-over-UDS client — deliberately not Ktor's HttpClient,
// which has no first-class Unix Domain Socket support. One connection per
// request, `Connection: close`, so parsing is just "read until EOF" rather
// than needing real keep-alive/chunked framing.
internal class DaemonIpcClient(
    private val socketPath: Path,
    private val keyDir: Path
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun startTunnel(request: StartTunnelRequest): TunnelStatusResponse {
        val body = json.encodeToString(request)
        return json.decodeFromString(request("POST", "/tunnel/start", body))
    }

    suspend fun stopTunnel(): TunnelStatusResponse {
        return json.decodeFromString(request("POST", "/tunnel/stop", ""))
    }

    suspend fun status(): TunnelStatusResponse {
        return json.decodeFromString(request("GET", "/tunnel/status", null))
    }

    suspend fun logsSince(cursor: Long): LogsResponse {
        return json.decodeFromString(request("GET", "/tunnel/logs?since=$cursor", null))
    }

    private suspend fun request(method: String, path: String, body: String?): String =
        withContext(Dispatchers.IO) {
            val keyFile = IpcSecret.getOrCreateSecret(keyDir)
            val secret = IpcSecret.readSecret(keyFile)
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val bodyBytes = (body ?: "").toByteArray(StandardCharsets.UTF_8)
            val signature = HmacSigner.sign(secret, timestamp, bodyBytes)

            val raw = try {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(socketPath))

                    val headers = buildString {
                        append("$method $path HTTP/1.1\r\n")
                        append("Host: localhost\r\n")
                        append("Connection: close\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${bodyBytes.size}\r\n")
                        append("${HmacHeaders.KEY_PATH}: $keyFile\r\n")
                        append("${HmacHeaders.TIMESTAMP}: $timestamp\r\n")
                        append("${HmacHeaders.SIGNATURE}: $signature\r\n")
                        append("\r\n")
                    }
                    channel.write(ByteBuffer.wrap(headers.toByteArray(StandardCharsets.UTF_8)))
                    if (bodyBytes.isNotEmpty()) {
                        channel.write(ByteBuffer.wrap(bodyBytes))
                    }
                    readUntilClosed(channel)
                }
            } catch (e: java.io.IOException) {
                // e.message here is the actual OS-level reason (permission
                // denied, no such file, connection refused, ...) — the
                // generic "not installed or not running" text shown to the
                // user doesn't distinguish these, so this is the only place
                // that captures which one it actually was.
                Logger.w(e, tag = TAG) { "$method $path: socket unreachable" }
                throw DaemonUnavailableException(e)
            }

            val (statusCode, responseBody) = parseHttpResponse(raw)
            if (statusCode !in 200..299) {
                Logger.w(tag = TAG) { "$method $path: daemon returned $statusCode: $responseBody" }
                error("olcbox-daemon returned $statusCode: $responseBody")
            }
            responseBody
        }

    private fun readUntilClosed(channel: SocketChannel): ByteArray {
        val buffer = ByteBuffer.allocate(8192)
        val output = ByteArrayOutputStream()
        while (true) {
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) break
            buffer.flip()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
        }
        return output.toByteArray()
    }

    private fun parseHttpResponse(raw: ByteArray): Pair<Int, String> {
        val text = String(raw, StandardCharsets.UTF_8)
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd < 0) error("Malformed HTTP response from olcbox-daemon")
        val statusLine = text.substring(0, headerEnd).lineSequence().first()
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: error("Malformed HTTP status line from olcbox-daemon: $statusLine")
        return statusCode to text.substring(headerEnd + 4)
    }

    private companion object {
        const val TAG = "DaemonIpcClient"
    }
}
