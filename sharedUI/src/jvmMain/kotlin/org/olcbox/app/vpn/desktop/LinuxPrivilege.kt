package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists

// Split out of the (now-deleted) LinuxTunController.kt — still referenced by
// DesktopVpnManager.startOlcRtcProcess() and OlcRtcConnectionChecker's own
// startOlcRtcProcess(), both of which keep a `privileged` parameter for a
// branch that's effectively dead now that Linux TUN mode goes through
// olcbox-daemon instead, but the code still compiles against this type.
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
