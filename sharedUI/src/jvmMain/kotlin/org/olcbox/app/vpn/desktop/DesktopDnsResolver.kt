package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path

internal object DesktopDnsResolver {
    private const val DEFAULT_DNS = "1.1.1.1:53"

    fun olcRtcDns(): String {
        return normalizeDns(System.getenv("OLCBOX_DNS"))
            ?: resolvConfDns(Path.of("/etc/resolv.conf"))
            ?: DEFAULT_DNS
    }

    internal fun resolvConfDns(path: Path): String? {
        return runCatching {
            Files.readAllLines(path)
                .asSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.startsWith("nameserver ") }
                .mapNotNull { normalizeDns(it.removePrefix("nameserver").trim()) }
                .firstOrNull()
        }.getOrNull()
    }

    internal fun normalizeDns(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.startsWith("[") && "]:" in trimmed) return trimmed
        if (trimmed.count { it == ':' } > 1) return "[$trimmed]:53"
        return if (':' in trimmed) trimmed else "$trimmed:53"
    }
}
