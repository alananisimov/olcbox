package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.data.routing.Ipv4Cidr
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists

internal class LinuxTunController(
    private val addLog: (String) -> Unit
) {
    private var routesInstalled = false
    private var activeBypassCidrs = emptyList<Ipv4Cidr>()

    suspend fun start(
        hevBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
        bypassCidrs: List<Ipv4Cidr> = emptyList()
    ): Process {
        activeBypassCidrs = bypassCidrs
        val upScript = writeUpScript(bypassCidrs)
        val downScript = writeDownScript(bypassCidrs)
        val config = writeConfig(socksPort, upScript, downScript)
        val process = startPrivilegedProcess(listOf(hevBinary.toString(), config.toString()))
        try {
            waitForTunReady(process)
            routesInstalled = true
            addLog("Linux TUN connected on $TUN_NAME")
            return process
        } catch (e: Exception) {
            stop(process)
            runCatching { runPrivilegedScript(writeDownScript(activeBypassCidrs)) }
                .onFailure { addLog("Linux TUN partial route cleanup failed: ${it.message}") }
            activeBypassCidrs = emptyList()
            throw e
        }
    }

    suspend fun stop(process: Process?) {
        stopProcess(process)

        if (routesInstalled) {
            waitForRoutesRemoved()
            runCatching { runPrivilegedScript(writeDownScript(activeBypassCidrs)) }
                .onFailure { addLog("Linux TUN route cleanup failed: ${it.message}") }
            routesInstalled = false
            activeBypassCidrs = emptyList()
        }
    }

    private fun writeConfig(socksPort: Int, upScript: Path, downScript: Path): Path {
        val config = DesktopPaths.appDataDir().resolve("linux-tun.yml")
        Files.writeString(
            config,
            configContent(
                socksPort = socksPort,
                postUpScript = upScript.toString(),
                preDownScript = downScript.toString()
            )
        )
        return config
    }

    private fun writeUpScript(bypassCidrs: List<Ipv4Cidr>): Path {
        return writeScript(
            name = "linux-tun-up.sh",
            body = upScriptContent(bypassCidrs)
        )
    }

    private fun writeDownScript(bypassCidrs: List<Ipv4Cidr>): Path {
        return writeScript(
            name = "linux-tun-down.sh",
            body = downScriptContent(bypassCidrs)
        )
    }

    private fun writeScript(name: String, body: String): Path {
        val script = DesktopPaths.appDataDir().resolve(name)
        Files.writeString(script, body)
        script.toFile().setExecutable(true, true)
        return script
    }

    private suspend fun waitForTunReady(process: Process) {
        val deadline = System.currentTimeMillis() + TUN_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                error(
                    buildString {
                        append("hev-socks5-tunnel exited before $TUN_NAME was ready")
                        if (output.isNotBlank()) append(": ").append(output)
                    }
                )
            }
            if (interfaceExists() && routeRuleExists()) return
            delay(TUN_READY_POLL_MS)
        }
        error("$TUN_NAME routes were not installed")
    }

    private suspend fun interfaceExists(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "link", "show", TUN_NAME)
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
                            trimmed.startsWith("$TUN_RULE_PREF:") ||
                                trimmed.contains("pref $TUN_RULE_PREF")
                            ) &&
                            trimmed.contains("lookup $ROUTE_TABLE")
                    }
        }.getOrDefault(false)
    }

    private suspend fun waitForRoutesRemoved() {
        val deadline = System.currentTimeMillis() + ROUTE_CLEANUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!routeRuleExists()) return
            delay(TUN_READY_POLL_MS)
        }
    }

    private suspend fun runPrivilegedScript(script: Path) {
        runPrivilegedCommand(listOf(script.toString()))
    }

    private suspend fun runPrivilegedCommand(command: List<String>): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(LinuxPrivilege.command(command))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("${command.joinToString(" ")} failed with code $exitCode: $output")
        }
        output
    }

    private fun startPrivilegedProcess(command: List<String>): Process {
        return ProcessBuilder(LinuxPrivilege.command(command))
            .redirectErrorStream(true)
            .start()
    }

    private fun stopProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    internal companion object {
        const val TUN_NAME = "olcbox0"
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val MAPDNS_NETWORK = "100.64.0.0"
        const val MAPDNS_NETMASK = "255.192.0.0"
        const val ROUTE_TABLE = "51820"
        const val ROOT_BYPASS_RULE_PREF = "10"
        const val CIDR_BYPASS_RULE_PREF_BASE = 11
        const val TUN_RULE_PREF = "20"
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val TUN_READY_POLL_MS = 100L
        const val ROUTE_CLEANUP_TIMEOUT_MS = 2_000L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L

        fun configContent(
            socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
            postUpScript: String? = null,
            preDownScript: String? = null
        ): String {
            return buildString {
                appendLine("tunnel:")
                appendLine("  name: $TUN_NAME")
                appendLine("  mtu: $TUN_MTU")
                appendLine("  multi-queue: false")
                appendLine("  ipv4: $TUN_IPV4_ADDRESS")
                if (!postUpScript.isNullOrBlank()) {
                    appendLine("  post-up-script: $postUpScript")
                }
                if (!preDownScript.isNullOrBlank()) {
                    appendLine("  pre-down-script: $preDownScript")
                }
                appendLine()
                appendLine("socks5:")
                appendLine("  address: ${PacServer.LOCAL_SOCKS_HOST}")
                appendLine("  port: $socksPort")
                appendLine("  udp: 'tcp'")
                appendLine("  pipeline: false")
                appendLine()
                appendLine("mapdns:")
                appendLine("  address: $MAPDNS_ADDRESS")
                appendLine("  port: 53")
                appendLine("  network: $MAPDNS_NETWORK")
                appendLine("  netmask: $MAPDNS_NETMASK")
                appendLine("  cache-size: 10000")
                appendLine()
                appendLine("misc:")
                appendLine("  task-stack-size: 24576")
                appendLine("  tcp-buffer-size: 4096")
                appendLine("  max-session-count: 1200")
                appendLine("  connect-timeout: 10000")
                appendLine("  tcp-read-write-timeout: 300000")
                appendLine("  udp-read-write-timeout: 60000")
                appendLine("  log-file: stderr")
                appendLine("  log-level: warn")
            }.trimEnd()
        }

        fun upScriptContent(bypassCidrs: List<Ipv4Cidr> = emptyList()): String {
            val bypassRuleLines = bypassCidrs
                .take(MAX_BYPASS_CIDR_RULES)
                .flatMapIndexed { index, cidr ->
                    val pref = CIDR_BYPASS_RULE_PREF_BASE + index
                    listOf(
                        "ip rule del to ${cidr.value} lookup main pref $pref 2>/dev/null || true",
                        "ip rule add to ${cidr.value} lookup main pref $pref"
                    )
                }
                .joinToString("\n")

            return """
                #!/bin/sh
                set -eu
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
                sysctl -w net.ipv4.conf.all.rp_filter=0 >/dev/null 2>&1 || true
                sysctl -w net.ipv4.conf.$TUN_NAME.rp_filter=0 >/dev/null 2>&1 || true
                ip link set $TUN_NAME up
                ip rule add uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF
$bypassRuleLines
                ip route add default dev $TUN_NAME table $ROUTE_TABLE
                ip rule add lookup $ROUTE_TABLE pref $TUN_RULE_PREF
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl dns $TUN_NAME $MAPDNS_ADDRESS >/dev/null 2>&1 || true
                  resolvectl domain $TUN_NAME '~.' >/dev/null 2>&1 || true
                  resolvectl default-route $TUN_NAME yes >/dev/null 2>&1 || true
                fi
            """.trimIndent()
        }

        fun downScriptContent(bypassCidrs: List<Ipv4Cidr> = emptyList()): String {
            val bypassRuleLines = bypassCidrs
                .take(MAX_BYPASS_CIDR_RULES)
                .mapIndexed { index, cidr ->
                    val pref = CIDR_BYPASS_RULE_PREF_BASE + index
                    "ip rule del to ${cidr.value} lookup main pref $pref 2>/dev/null || true"
                }
                .joinToString("\n")

            return """
                #!/bin/sh
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
$bypassRuleLines
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl revert $TUN_NAME >/dev/null 2>&1 || true
                fi
            """.trimIndent()
        }

        private const val MAX_BYPASS_CIDR_RULES = 64
    }
}

internal object LinuxPrivilege {
    fun command(command: List<String>): List<String> {
        if (isRoot()) return command
        val preferred = System.getenv("OLCBOX_LINUX_PRIVILEGE")?.lowercase()
        return when {
            preferred == "sudo" -> listOf("sudo", "-n") + command
            preferred == "pkexec" -> listOf("pkexec") + command
            executableExists("pkexec") -> listOf("pkexec") + command
            else -> listOf("sudo", "-n") + command
        }
    }

    private fun isRoot(): Boolean {
        return runCatching {
            val process = ProcessBuilder("id", "-u")
                .redirectErrorStream(true)
                .start()
            val uid = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(1, TimeUnit.SECONDS) && uid == "0"
        }.getOrDefault(false)
    }

    private fun executableExists(name: String): Boolean {
        val path = System.getenv("PATH").orEmpty()
        return path.split(':')
            .filter { it.isNotBlank() }
            .map { Path(it).resolve(name) }
            .any { it.exists() && Files.isExecutable(it) }
    }
}
