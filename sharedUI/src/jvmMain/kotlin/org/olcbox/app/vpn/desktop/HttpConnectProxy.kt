package org.olcbox.app.vpn.desktop

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpConnectProxy(
    private val host: String = HTTP_PROXY_HOST,
    private val port: Int = HTTP_PROXY_PORT,
    private val connector: Connector = Connector { targetHost, targetPort, socksTarget ->
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksTarget.host, socksTarget.port))).apply {
            connect(InetSocketAddress.createUnresolved(targetHost, targetPort), CONNECT_TIMEOUT_MS)
        }
    }
) : AutoCloseable {
    private val lock = Any()
    @Volatile
    private var socksTarget = SocksTarget(PacServer.LOCAL_SOCKS_HOST, PacServer.LOCAL_SOCKS_PORT)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService = newExecutor()

    val boundPort: Int
        get() = serverSocket?.localPort ?: port

    fun start(
        socksHost: String = PacServer.LOCAL_SOCKS_HOST,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT
    ) {
        updateSocksTarget(socksHost, socksPort)
        synchronized(lock) {
            if (serverSocket != null) return
            executor = newExecutor()
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(host, port))
            }
            executor.execute(::acceptLoop)
        }
    }

    fun updateSocksTarget(
        socksHost: String = PacServer.LOCAL_SOCKS_HOST,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT
    ) {
        socksTarget = SocksTarget(
            host = socksHost.ifBlank { PacServer.LOCAL_SOCKS_HOST },
            port = socksPort
        )
    }

    fun stop() {
        synchronized(lock) {
            runCatching { serverSocket?.close() }
            serverSocket = null
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    override fun close() = stop()

    private fun acceptLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val client = runCatching { serverSocket?.accept() }.getOrNull() ?: return
            executor.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { clientSocket ->
            clientSocket.soTimeout = READ_TIMEOUT_MS
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            val requestLine = input.readHttpLine() ?: return
            val headers = input.readHeaders()
            val parts = requestLine.split(" ", limit = 3)
            if (parts.size != 3) {
                output.writeResponse(400, "Bad Request")
                return
            }

            if (parts[0].equals("CONNECT", ignoreCase = true)) {
                handleConnect(parts[1], clientSocket, output)
            } else {
                handleHttpRequest(parts[0], parts[1], parts[2], headers, clientSocket, output)
            }
        }
    }

    private fun handleConnect(authority: String, client: Socket, clientOutput: OutputStream) {
        val target = parseAuthority(authority) ?: run {
            clientOutput.writeResponse(400, "Bad CONNECT target")
            return
        }
        connector.connect(target.host, target.port, socksTarget).use { upstream ->
            client.soTimeout = 0
            upstream.soTimeout = 0
            clientOutput.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            clientOutput.flush()
            pumpBothWays(client, upstream)
        }
    }

    private fun handleHttpRequest(
        method: String,
        target: String,
        version: String,
        headers: List<String>,
        client: Socket,
        clientOutput: OutputStream
    ) {
        val request = parseHttpTarget(target, headers) ?: run {
            clientOutput.writeResponse(400, "Bad HTTP target")
            return
        }
        connector.connect(request.host, request.port, socksTarget).use { upstream ->
            client.soTimeout = 0
            upstream.soTimeout = 0
            val upstreamOutput = upstream.getOutputStream()
            upstreamOutput.write("$method ${request.path} $version\r\n".toByteArray(Charsets.ISO_8859_1))
            headers
                .filterNot { it.startsWith("Proxy-Connection:", ignoreCase = true) }
                .forEach { upstreamOutput.write("$it\r\n".toByteArray(Charsets.ISO_8859_1)) }
            upstreamOutput.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            upstreamOutput.flush()
            pumpBothWays(client, upstream)
        }
    }

    private fun pumpBothWays(left: Socket, right: Socket) {
        val done = CountDownLatch(1)
        val a = executor.submit { left.getInputStream().copyToAndShutdown(right, done) }
        val b = executor.submit { right.getInputStream().copyToAndShutdown(left, done) }

        runCatching { done.await() }
        runCatching { left.close() }
        runCatching { right.close() }
        runCatching { a.get(1, TimeUnit.SECONDS) }
        runCatching { b.get(1, TimeUnit.SECONDS) }
    }

    private fun InputStream.copyToAndShutdown(target: Socket, done: CountDownLatch) {
        try {
            runCatching {
                copyTo(target.getOutputStream())
                target.shutdownOutput()
            }
        } finally {
            done.countDown()
        }
    }

    data class SocksTarget(
        val host: String,
        val port: Int
    )

    fun interface Connector {
        fun connect(host: String, port: Int, socksTarget: SocksTarget): Socket
    }

    companion object {
        const val HTTP_PROXY_HOST = "127.0.0.1"
        const val HTTP_PROXY_PORT = 10810
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        private fun newExecutor(): ExecutorService {
            return Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "OlcboxHttpConnectProxy").apply { isDaemon = true }
            }
        }

        private fun InputStream.readHeaders(): List<String> {
            val headers = mutableListOf<String>()
            while (true) {
                val line = readHttpLine() ?: break
                if (line.isEmpty()) break
                headers.add(line)
            }
            return headers
        }

        private fun InputStream.readHttpLine(): String? {
            val out = ByteArrayOutputStream()
            while (out.size() < 8192) {
                val value = read()
                if (value < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1)
                if (value == '\n'.code) break
                if (value != '\r'.code) out.write(value)
            }
            return out.toString(Charsets.ISO_8859_1)
        }

        private fun OutputStream.writeResponse(status: Int, message: String) {
            write("HTTP/1.1 $status $message\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }

        private fun parseAuthority(value: String): Target? {
            val host = value.substringBeforeLast(":", missingDelimiterValue = "")
            val port = value.substringAfterLast(":", missingDelimiterValue = "").toIntOrNull()
            if (host.isBlank() || port == null || port !in 1..65535) return null
            return Target(host, port, "")
        }

        private fun parseHttpTarget(target: String, headers: List<String>): Target? {
            return if (target.startsWith("http://", ignoreCase = true)) {
                val uri = URI(target)
                Target(
                    host = uri.host ?: return null,
                    port = uri.port.takeIf { it > 0 } ?: 80,
                    path = buildString {
                        append(uri.rawPath.takeIf { !it.isNullOrBlank() } ?: "/")
                        if (!uri.rawQuery.isNullOrBlank()) append("?").append(uri.rawQuery)
                    }
                )
            } else {
                val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    ?.substringAfter(":")
                    ?.trim()
                    ?: return null
                val authority = parseAuthority(hostHeader) ?: Target(hostHeader, 80, "")
                authority.copy(path = target.ifBlank { "/" })
            }
        }
    }

    private data class Target(
        val host: String,
        val port: Int,
        val path: String
    )
}
