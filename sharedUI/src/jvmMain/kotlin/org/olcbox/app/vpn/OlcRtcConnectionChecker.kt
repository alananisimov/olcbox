package org.olcbox.app.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.LinuxPrivilege
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import org.olcbox.app.vpn.desktop.PacServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object OlcRtcConnectionChecker {

    suspend fun check(
        locationConfig: LocationConfig,
        deviceId: String = DEFAULT_DEVICE_ID,
        privileged: Boolean = false
    ): Long? {
        return withContext(Dispatchers.IO) {
            val config = locationConfig.normalized()
            if (!config.isComplete()) return@withContext null

            val nativeLib = OlcRtcNativeLib.INSTANCE
            if (nativeLib != null) {
                repeat(CONNECTION_CHECK_ATTEMPTS) {
                    val socksPort = allocateLocalPort()
                    val result = runCatching {
                        val latency = nativeLib.Check(
                            config.bypassProvider,
                            config.transport,
                            config.id,
                            deviceId,
                            config.key,
                            socksPort.toLong(),
                            OLC_READY_TIMEOUT_MS,
                            config.vp8Fps.toLong(),
                            config.vp8Batch.toLong()
                        )
                        if (latency >= 0) latency else null
                    }.getOrNull()

                    if (result != null && result > 0L) {
                        return@withContext result
                    }
                }
                return@withContext null
            }

            repeat(CONNECTION_CHECK_ATTEMPTS) {
                val result = runCatching {
                    checkOnce(
                        config = config,
                        privileged = privileged
                    )
                }.getOrNull()

                if (result != null && result > 0L) {
                    return@withContext result
                }
            }

            null
        }
    }

    suspend fun ping(
        locationConfig: LocationConfig,
        deviceId: String = DEFAULT_DEVICE_ID,
        privileged: Boolean = false
    ): Long? {
        return withContext(Dispatchers.IO) {
            val config = locationConfig.normalized()
            if (!config.isComplete()) return@withContext null

            val nativeLib = OlcRtcNativeLib.INSTANCE
            if (nativeLib != null) {
                repeat(HTTP_PING_ATTEMPTS) {
                    val socksPort = allocateLocalPort()
                    val result = runCatching {
                        val latency = nativeLib.Ping(
                            config.bypassProvider,
                            config.transport,
                            config.id,
                            deviceId,
                            config.key,
                            socksPort.toLong(),
                            HTTP_PING_TIMEOUT_MS,
                            HTTP_PING_URL,
                            config.vp8Fps.toLong(),
                            config.vp8Batch.toLong()
                        )
                        if (latency >= 0) latency else null
                    }.onFailure {
                        println("OlcRtcConnectionChecker: Native ping failed: ${it.message}")
                    }.getOrNull()

                    if (result != null && result >= 0L) {
                        return@withContext result
                    }
                }
                return@withContext null
            }

            repeat(HTTP_PING_ATTEMPTS) {
                val result = runCatching {
                    pingOnce(
                        config = config,
                        privileged = privileged
                    )
                }.onFailure {
                    println("OlcRtcConnectionChecker: HTTP ping failed: ${it.message}")
                }.getOrNull()

                if (result != null && result >= 0L) {
                    return@withContext result
                }
            }

            null
        }
    }

    private suspend fun checkOnce(
        config: LocationConfig,
        privileged: Boolean
    ): Long = coroutineScope {
        val socksPort = allocateLocalPort()
        val ready = CompletableDeferred<Unit>()

        val process = startOlcRtcProcessWithFallback(
            scope = this,
            config = config,
            socksPort = socksPort,
            ready = ready,
            privileged = privileged
        )

        val startedAt = System.currentTimeMillis()

        try {
            waitForOlcRtcReady(
                process = process,
                ready = ready,
                socksPort = socksPort
            )

            System.currentTimeMillis() - startedAt
        } finally {
            stopProcess(process)
        }
    }

    private suspend fun pingOnce(
        config: LocationConfig,
        privileged: Boolean
    ): Long = coroutineScope {
        val socksPort = allocateLocalPort()
        val ready = CompletableDeferred<Unit>()

        val process = startOlcRtcProcessWithFallback(
            scope = this,
            config = config,
            socksPort = socksPort,
            ready = ready,
            privileged = privileged
        )

        try {
            waitForOlcRtcReady(
                process = process,
                ready = ready,
                socksPort = socksPort
            )

            httpPingThroughSocks(socksPort)
        } finally {
            stopProcess(process)
        }
    }

    private fun startOlcRtcProcessWithFallback(
        scope: CoroutineScope,
        config: LocationConfig,
        socksPort: Int,
        ready: CompletableDeferred<Unit>,
        privileged: Boolean
    ): Process {
        val binaries = DesktopNativeAssets.resolveOlcRtcBinaryCandidates()
        var lastException: Exception? = null

        for (binary in binaries) {
            try {
                return startOlcRtcProcess(
                    scope = scope,
                    binary = binary,
                    config = config,
                    socksPort = socksPort,
                    ready = ready,
                    privileged = privileged
                )
            } catch (e: Exception) {
                lastException = e

                if (binary == binaries.last()) {
                    break
                }

                println(
                    "OlcRtcConnectionChecker: olcRTC start failed for ${binary.fileName}: ${e.message}. " +
                            "Retrying with fallback binary."
                )
            }
        }

        throw lastException ?: error("olcRTC binary failed to start")
    }

    private fun startOlcRtcProcess(
        scope: CoroutineScope,
        binary: Path,
        config: LocationConfig,
        socksPort: Int,
        ready: CompletableDeferred<Unit>,
        privileged: Boolean
    ): Process {
        val normalized = config.normalized()
        val dataDir = DesktopNativeAssets.resolveOlcRtcDataDir()

        val command = OlcRtcCommand(
            binary = binary,
            location = normalized,
            socksHost = PacServer.LOCAL_SOCKS_HOST,
            socksPort = socksPort,
            dataDir = dataDir
        )
        val configPath = writeOlcRtcClientConfig(command)

        val processBuilder = ProcessBuilder(
            if (privileged) LinuxPrivilege.command(command.args(configPath)) else command.args(configPath)
        ).redirectErrorStream(true)

        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(configPath) }
            throw e
        }

        coroutineScopeReader(scope, process, ready)
        scope.launch(Dispatchers.IO) {
            runCatching { process.waitFor() }
            runCatching { Files.deleteIfExists(configPath) }
        }

        return process
    }

    private fun writeOlcRtcClientConfig(command: OlcRtcCommand): Path {
        val runtimeDir = DesktopNativeAssets.resolveOlcRtcDataDir().parent.resolve("runtime")
        Files.createDirectories(runtimeDir)
        val path = Files.createTempFile(runtimeDir, "olcrtc-check-", ".yaml")
        Files.writeString(path, command.yaml(), StandardCharsets.UTF_8)
        return path
    }

    private fun coroutineScopeReader(
        scope: CoroutineScope,
        process: Process,
        ready: CompletableDeferred<Unit>
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.contains("SOCKS5 server listening", ignoreCase = true)) {
                            ready.complete(Unit)
                        }
                    }
                }
            } catch (_: Exception) {
                // Process was stopped or stream was closed.
            }
        }
    }

    private suspend fun waitForOlcRtcReady(
        process: Process,
        ready: CompletableDeferred<Unit>,
        socksPort: Int
    ) {
        val deadline = System.currentTimeMillis() + OLC_READY_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
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

    private fun httpPingThroughSocks(socksPort: Int): Long {
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, socksPort)
        )

        val startedAt = System.currentTimeMillis()

        val connection = URL(HTTP_PING_URL).openConnection(proxy) as HttpURLConnection

        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS.toInt()
            connection.readTimeout = HTTP_READ_TIMEOUT_MS.toInt()
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", CurrentAppInfo.userAgent)

            val code = connection.responseCode

            if (code !in 200..399) {
                error("Unexpected HTTP status: $code")
            }

            return System.currentTimeMillis() - startedAt
        } finally {
            connection.disconnect()
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

    private fun allocateLocalPort(): Int {
        return ServerSocket(0).use { serverSocket ->
            serverSocket.localPort
        }
    }

    private fun stopProcess(process: Process?) {
        if (process == null) return
        if (!process.isAlive) return

        process.toHandle().descendants().forEach {
            it.destroy()
        }

        process.destroy()

        if (!process.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.toHandle().descendants().forEach {
                it.destroyForcibly()
            }

            process.destroyForcibly()
            process.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private const val CONNECTION_CHECK_ATTEMPTS = 2
    private const val HTTP_PING_ATTEMPTS = 1

    private const val OLC_READY_TIMEOUT_MS = 25_000L
    private const val READY_POLL_INTERVAL_MS = 100L
    private const val TCP_CONNECT_TIMEOUT_MS = 250L

    private const val HTTP_PING_TIMEOUT_MS = 8_000L
    private const val HTTP_CONNECT_TIMEOUT_MS = 8_000L
    private const val HTTP_READ_TIMEOUT_MS = 8_000L
    private const val HTTP_PING_URL = "https://www.google.com/generate_204"

    private const val PROCESS_STOP_TIMEOUT_MS = 3_000L
    private const val PROCESS_KILL_TIMEOUT_MS = 1_000L
    private const val DEFAULT_DEVICE_ID = "olcbox"
}
