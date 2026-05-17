package org.olcbox.app.vpn.desktop

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpConnectProxyTest {
    @Test
    fun connectRequestTunnelsBytesToTarget() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { target ->
            val targetPort = target.localPort
            val targetThread = thread(start = true, isDaemon = true) {
                target.accept().use { socket ->
                    val buffer = ByteArray(4)
                    socket.getInputStream().readNBytes(buffer, 0, buffer.size)
                    socket.getOutputStream().write(buffer.reversedArray())
                    socket.getOutputStream().flush()
                }
            }

            HttpConnectProxy(
                port = 0,
                connector = HttpConnectProxy.Connector { host, port, _ ->
                    Socket().apply { connect(InetSocketAddress(host, port), 2_000) }
                }
            ).use { proxy ->
                proxy.start(socksHost = "127.0.0.1", socksPort = 10808)
                Socket("127.0.0.1", proxy.boundPort).use { client ->
                    client.soTimeout = 2_000
                    client.getOutputStream().write(
                        "CONNECT 127.0.0.1:$targetPort HTTP/1.1\r\nHost: 127.0.0.1:$targetPort\r\n\r\n"
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    client.getOutputStream().flush()

                    val response = readHeaders(client)
                    assertTrue(response.startsWith("HTTP/1.1 200"))

                    client.getOutputStream().write(byteArrayOf(1, 2, 3, 4))
                    client.getOutputStream().flush()

                    assertEquals(listOf<Byte>(4, 3, 2, 1), client.getInputStream().readNBytes(4).toList())
                }
            }

            targetThread.join(2_000)
        }
    }

    private fun readHeaders(socket: Socket): String {
        val bytes = mutableListOf<Byte>()
        val input = socket.getInputStream()
        while (!bytes.endsWithHeaderTerminator()) {
            val value = input.read()
            if (value < 0) break
            bytes.add(value.toByte())
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun List<Byte>.endsWithHeaderTerminator(): Boolean {
        return size >= 4 &&
                this[size - 4] == '\r'.code.toByte() &&
                this[size - 3] == '\n'.code.toByte() &&
                this[size - 2] == '\r'.code.toByte() &&
                this[size - 1] == '\n'.code.toByte()
    }
}
