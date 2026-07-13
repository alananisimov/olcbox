package org.olcbox.daemon.tunnel

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.olcbox.daemon.ipc.DaemonPaths
import org.olcbox.daemon.ipc.LogsResponse
import org.olcbox.daemon.ipc.StartTunnelRequest
import org.olcbox.daemon.ipc.TunnelState
import org.olcbox.daemon.ipc.TunnelStatusResponse

// Owns the two privileged child processes (olcRTC + hev-socks5-tunnel) for
// Linux TUN mode. The daemon runs as root, so it launches both directly —
// no pkexec/sudo per toggle. It is deliberately dumb about *what* it runs:
// the client sends a fully-rendered olcRTC YAML config, this class just
// writes it to disk and manages the process lifecycle around it.
internal class TunnelSupervisor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val mutex = Mutex()
    private val logBuffer = LogBuffer()

    private var olcRtcProcess: Process? = null
    private var hevProcess: Process? = null
    private var generation = 0L

    @Volatile
    private var status: TunnelStatusResponse = TunnelStatusResponse(state = TunnelState.STOPPED)

    fun currentStatus(): TunnelStatusResponse = status

    fun logsSince(cursor: Long): LogsResponse {
        val (entries, next) = logBuffer.since(cursor)
        return LogsResponse(entries = entries, nextCursor = next)
    }

    suspend fun start(request: StartTunnelRequest): TunnelStatusResponse = mutex.withLock {
        val myGeneration = ++generation
        status = TunnelStatusResponse(state = TunnelState.STARTING)
        try {
            stopProcessesLocked()

            // RUNTIME_DIR itself must stay traversable (systemd's default
            // 0755) so unprivileged clients can still reach daemon.sock —
            // only this subdirectory, holding the room key and tunnel
            // scripts, gets locked down. Never reopened: nothing outside the
            // root daemon (and its root-spawned children) ever needs it.
            val configDir = Path.of(DaemonPaths.CONFIG_DIR)
            Files.createDirectories(configDir)
            Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("rwx------"))

            val olcRtcConfigPath = configDir.resolve("olcrtc.yaml")
            Files.writeString(olcRtcConfigPath, request.olcRtcConfigYaml)

            val olcRtcReady = CompletableDeferred<Unit>()
            val olcRtcFailed = CompletableDeferred<String>()
            val olcRtcBinary = Path.of(DaemonPaths.BIN_DIR).resolve(olcRtcBinaryName())
            val rtcProcess = ProcessBuilder(olcRtcBinary.toString(), olcRtcConfigPath.toString())
                .redirectErrorStream(true)
                .start()
            olcRtcProcess = rtcProcess
            pumpLogs(rtcProcess, SOURCE_RTC, olcRtcReady, olcRtcFailed)

            waitForOlcRtcReady(rtcProcess, olcRtcReady, olcRtcFailed)
            checkGeneration(myGeneration)

            startHevTun(configDir, request.socksPort, myGeneration)
            checkGeneration(myGeneration)

            status = TunnelStatusResponse(state = TunnelState.CONNECTED)
            status
        } catch (e: Exception) {
            stopProcessesLocked()
            status = TunnelStatusResponse(state = TunnelState.ERROR, message = e.message ?: "unknown error")
            status
        }
    }

    suspend fun stop(): TunnelStatusResponse = mutex.withLock {
        generation++
        stopProcessesLocked()
        status = TunnelStatusResponse(state = TunnelState.STOPPED)
        status
    }

    private fun checkGeneration(expected: Long) {
        if (expected != generation) error("superseded by a newer start() call")
    }

    private suspend fun waitForOlcRtcReady(
        process: Process,
        ready: CompletableDeferred<Unit>,
        failed: CompletableDeferred<String>
    ) {
        val deadline = System.currentTimeMillis() + OLC_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ready.isCompleted) return
            if (failed.isCompleted) error("olcRTC failed before it was ready: ${failed.await()}")
            if (!process.isAlive) error("olcRTC exited before SOCKS5 was ready")
            delay(READY_POLL_INTERVAL_MS)
        }
        error("olcRTC start timed out")
    }

    private suspend fun startHevTun(configDir: Path, socksPort: Int, myGeneration: Long) {
        val upScript = writeScript(configDir, "hev-up.sh", HevTunConfig.upScriptContent())
        val downScript = writeScript(configDir, "hev-down.sh", HevTunConfig.downScriptContent())
        val hevConfig = configDir.resolve("hev-tun.yml")
        Files.writeString(
            hevConfig,
            HevTunConfig.configContent(
                socksPort = socksPort,
                socksHost = HevTunConfig.SOCKS_HOST,
                postUpScript = upScript.toString(),
                preDownScript = downScript.toString()
            )
        )

        val hevBinary = Path.of(DaemonPaths.BIN_DIR).resolve("hev-socks5-tunnel")
        val process = ProcessBuilder(hevBinary.toString(), hevConfig.toString())
            .redirectErrorStream(true)
            .start()
        hevProcess = process
        pumpLogs(process, SOURCE_TUN, null, null)

        try {
            waitForTunReady(process)
        } catch (e: Exception) {
            checkGeneration(myGeneration)
            throw e
        }
    }

    private suspend fun waitForTunReady(process: Process) {
        val deadline = System.currentTimeMillis() + TUN_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) error("hev-socks5-tunnel exited before ${HevTunConfig.TUN_NAME} was ready")
            if (interfaceExists() && routeRuleExists()) return
            delay(READY_POLL_INTERVAL_MS)
        }
        error("${HevTunConfig.TUN_NAME} routes were not installed")
    }

    private fun pumpLogs(
        process: Process,
        source: String,
        ready: CompletableDeferred<Unit>?,
        failed: CompletableDeferred<String>?
    ) {
        scope.launch {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        logBuffer.append(source, line)
                        if (ready != null && !ready.isCompleted &&
                            line.contains("SOCKS5 server listening", ignoreCase = true)
                        ) {
                            ready.complete(Unit)
                        }
                        if (failed != null && !failed.isCompleted && isFatalOlcRtcStartupLine(line)) {
                            failed.complete(line)
                        }
                    }
                }
            }
        }
    }

    // Tears down hev first (stop capturing system traffic into the TUN
    // before killing what it depends on), then olcRTC. Implements the
    // device-busy fix: SIGINT first (the only signal hev-socks5-tunnel
    // actually handles gracefully, see hev-main.c — SIGTERM is instant-kill
    // with zero cleanup there), escalating to SIGTERM then SIGKILL. Once the
    // process is confirmed dead, additionally waits for the TUN interface
    // itself to disappear (unregister_netdevice is async/RCU-based in the
    // kernel) before allowing a subsequent start() to proceed — not just the
    // routing rule, which is all the previous pkexec-based implementation
    // ever checked.
    private suspend fun stopProcessesLocked() {
        val hev = hevProcess
        hevProcess = null
        if (hev != null) {
            killWithEscalation(hev)

            waitForRoutesRemoved()
            if (routeRuleExists()) {
                runCatching { runScript(Path.of(DaemonPaths.CONFIG_DIR).resolve("hev-down.sh")) }
            }

            waitForInterfaceRemoved()
            if (interfaceExists()) {
                runCatching { runCommand(listOf("ip", "link", "delete", HevTunConfig.TUN_NAME)) }
            }
        }

        val rtc = olcRtcProcess
        olcRtcProcess = null
        if (rtc != null) {
            killWithEscalation(rtc)
        }
    }

    private fun killWithEscalation(process: Process) {
        if (!process.isAlive) return
        val pids = (process.toHandle().descendants().map { it.pid() }.toList() + process.pid()).distinct()

        sendSignal(pids, "-INT")
        if (process.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return

        sendSignal(pids, "-TERM")
        if (process.waitFor(PROCESS_TERM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return

        sendSignal(pids, "-KILL")
        process.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    // The daemon is already root, so signals go straight to the target PIDs
    // — no pkexec/sudo wrapping, and no ambiguity about whether an
    // unprivileged sender's signal actually reached a root-owned child.
    private fun sendSignal(pids: List<Long>, signal: String) {
        if (pids.isEmpty()) return
        runCatching {
            ProcessBuilder(listOf("kill", signal) + pids.map(Long::toString))
                .redirectErrorStream(true)
                .start()
                .waitFor(2, TimeUnit.SECONDS)
        }
    }

    private suspend fun waitForRoutesRemoved() {
        val deadline = System.currentTimeMillis() + ROUTE_CLEANUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!routeRuleExists()) return
            delay(READY_POLL_INTERVAL_MS)
        }
    }

    private suspend fun waitForInterfaceRemoved() {
        val deadline = System.currentTimeMillis() + INTERFACE_CLEANUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!interfaceExists()) return
            delay(READY_POLL_INTERVAL_MS)
        }
    }

    private suspend fun interfaceExists(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "link", "show", HevTunConfig.TUN_NAME)
                .redirectErrorStream(true)
                .start()
            process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private suspend fun routeRuleExists(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "rule", "show")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(1, TimeUnit.SECONDS) &&
                process.exitValue() == 0 &&
                output.lineSequence().any { line ->
                    val trimmed = line.trim()
                    (
                        trimmed.startsWith("${HevTunConfig.TUN_RULE_PREF}:") ||
                            trimmed.contains("pref ${HevTunConfig.TUN_RULE_PREF}")
                        ) &&
                        trimmed.contains("lookup ${HevTunConfig.ROUTE_TABLE}")
                }
        }.getOrDefault(false)
    }

    private suspend fun runScript(script: Path) = runCommand(listOf(script.toString()))

    private suspend fun runCommand(command: List<String>): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("${command.joinToString(" ")} failed with code $exitCode: $output")
        }
        output
    }

    private fun writeScript(dir: Path, name: String, body: String): Path {
        val script = dir.resolve(name)
        Files.writeString(script, body)
        script.toFile().setExecutable(true, true)
        return script
    }

    private fun olcRtcBinaryName(): String = "olcrtc-linux-${desktopArch()}"

    private fun desktopArch(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when (arch) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> error("Unsupported daemon architecture: $arch")
        }
    }

    internal companion object {
        private const val SOURCE_RTC = "rtc"
        private const val SOURCE_TUN = "tun"

        private const val OLC_READY_TIMEOUT_MS = 25_000L
        private const val TUN_READY_TIMEOUT_MS = 10_000L
        private const val READY_POLL_INTERVAL_MS = 100L
        private const val ROUTE_CLEANUP_TIMEOUT_MS = 2_000L
        private const val INTERFACE_CLEANUP_TIMEOUT_MS = 3_000L
        private const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        private const val PROCESS_TERM_TIMEOUT_MS = 1_000L
        private const val PROCESS_KILL_TIMEOUT_MS = 1_000L

        internal fun isFatalOlcRtcStartupLine(line: String): Boolean {
            val text = line.lowercase()
            return "failed to connect link" in text ||
                "join room failed" in text ||
                ("get room token" in text && "failed" in text) ||
                ("transport connect" in text && "failed" in text)
        }
    }
}
