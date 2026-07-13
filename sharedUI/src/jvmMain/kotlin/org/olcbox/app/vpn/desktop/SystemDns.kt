package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.io.File
import java.util.concurrent.TimeUnit

internal object SystemDns {

    private const val FALLBACK = "1.1.1.1"
    private const val DNS_PORT = "53"
    private const val PROCESS_TIMEOUT_SECONDS = 3L

    fun serverAddress(): String {
        val ip = resolveIp() ?: FALLBACK
        val host = if (ip.contains(':')) "[$ip]" else ip
        return "$host:$DNS_PORT"
    }

    private fun resolveIp(): String? {
        val candidate = when (DesktopPaths.os) {
            DesktopOs.Linux -> fromResolvConf()
            DesktopOs.Windows -> fromPowerShell()
            DesktopOs.MacOS -> fromScutil()
            DesktopOs.Other -> null
        }
        return candidate?.takeIf { isValidIp(it) }
    }

    private fun fromResolvConf(): String? {
        return runCatching {
            File("/etc/resolv.conf")
                .readLines()
                .firstOrNull { it.trim().startsWith("nameserver") }
                ?.trim()
                ?.removePrefix("nameserver")
                ?.trim()
        }.getOrNull()
    }

    private fun fromPowerShell(): String? {
        return runProcessLine(
            ProcessBuilder(
                "powershell", "-NoProfile", "-Command",
                "Get-DnsClientServerAddress -AddressFamily IPv4 | " +
                    "Select-Object -ExpandProperty ServerAddresses | " +
                    "Select-Object -First 1"
            )
        ) { lines -> lines.firstOrNull { isValidIp(it.trim()) }?.trim() }
    }

    private fun fromScutil(): String? {
        return runProcessLine(ProcessBuilder("scutil", "--dns")) { lines ->
            lines
                .firstOrNull { it.trim().contains("nameserver[0]") }
                ?.substringAfter(":")
                ?.trim()
        }
    }

    // Enforces the timeout on the *wait*, not the read: readText() blocks until
    // the process closes stdout, so reading before waitFor would let a hung
    // child (powershell/scutil never exiting) stall this indefinitely regardless
    // of the timeout below. Waiting first bounds the total call to
    // PROCESS_TIMEOUT_SECONDS; reading only happens once the process has
    // actually exited, so it never blocks.
    private fun runProcessLine(builder: ProcessBuilder, parse: (List<String>) -> String?): String? {
        val process = runCatching {
            builder.redirectErrorStream(true).start()
        }.getOrNull() ?: return null

        return try {
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return null
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            parse(output.lines())
        } catch (_: Exception) {
            null
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun isValidIp(value: String): Boolean {
        if (value.isBlank() || value.any { it.isWhitespace() }) return false
        return isValidIpv4(value) || isValidIpv6(value)
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull() ?: return@all false
            n in 0..255 && part == n.toString()
        }
    }

    private fun isValidIpv6(value: String): Boolean {
        // Strip a zone index (e.g. fe80::1%eth0) before validating.
        val withoutZone = value.substringBefore('%')
        if (!withoutZone.contains(':')) return false
        val groups = withoutZone.split(':')
        if (groups.size < 3 || groups.size > 8) return false
        return groups.all { group ->
            group.isEmpty() || (group.length <= 4 && group.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })
        }
    }
}
