package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.net.URI

internal interface DesktopProxyController {
    suspend fun enable(pacUrl: String)
    suspend fun restore()

    companion object {
        fun current(): DesktopProxyController {
            return when (DesktopPaths.os) {
                DesktopOs.MacOS -> MacOsProxyController()
                DesktopOs.Windows -> WindowsProxyController()
                DesktopOs.Linux -> UnsupportedProxyController()
                DesktopOs.Other -> UnsupportedProxyController()
            }
        }
    }
}

internal class UnsupportedProxyController : DesktopProxyController {
    override suspend fun enable(pacUrl: String) {
        error("System proxy mode supports macOS and Windows")
    }

    override suspend fun restore() = Unit
}

internal data class MacOsAutoProxyState(
    val service: String,
    val enabled: Boolean,
    val url: String?
)

internal class MacOsProxyController : DesktopProxyController {
    private var backup: List<MacOsAutoProxyState>? = null

    override suspend fun enable(pacUrl: String) {
        validatePacUrl(pacUrl)
        val services = enabledNetworkServices()
        backup = services.map { service ->
            readAutoProxyState(service)
        }
        enableCommands(services, pacUrl).forEach { runCommand(it) }
    }

    override suspend fun restore() {
        val states = backup ?: return
        restoreCommands(states).forEach { command ->
            runCatching { runCommand(command) }
        }
        backup = null
    }

    private suspend fun enabledNetworkServices(): List<String> {
        return runCommand(listOf("networksetup", "-listallnetworkservices"))
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("An asterisk") && !it.startsWith("*") }
            .toList()
    }

    private suspend fun readAutoProxyState(service: String): MacOsAutoProxyState {
        val output = runCommand(listOf("networksetup", "-getautoproxyurl", service))
        val enabled = output.lineSequence()
            .firstOrNull { it.startsWith("Enabled:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.equals("Yes", ignoreCase = true) == true
        val url = output.lineSequence()
            .firstOrNull { it.startsWith("URL:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "(null)" }
        return MacOsAutoProxyState(service, enabled, url)
    }

    companion object {
        fun enableCommands(services: List<String>, pacUrl: String): List<List<String>> {
            return services.flatMap { service ->
                listOf(
                    listOf("networksetup", "-setautoproxyurl", service, pacUrl),
                    listOf("networksetup", "-setautoproxystate", service, "on")
                )
            }
        }

        fun restoreCommands(states: List<MacOsAutoProxyState>): List<List<String>> {
            return states.flatMap { state ->
                if (state.enabled && !state.url.isNullOrBlank()) {
                    listOf(
                        listOf("networksetup", "-setautoproxyurl", state.service, state.url),
                        listOf("networksetup", "-setautoproxystate", state.service, "on")
                    )
                } else {
                    listOf(listOf("networksetup", "-setautoproxystate", state.service, "off"))
                }
            }
        }
    }
}

internal data class WindowsProxyState(
    val proxyEnable: String?,
    val proxyServer: String?,
    val proxyOverride: String?,
    val autoConfigUrl: String?
)

internal class WindowsProxyController : DesktopProxyController {
    private var backup: WindowsProxyState? = null

    override suspend fun enable(pacUrl: String) {
        validatePacUrl(pacUrl)
        backup = readState()
        enableCommands(pacUrl).forEach { runCommand(it) }
        refreshProxySettings()
    }

    override suspend fun restore() {
        val state = backup ?: return
        restoreCommands(state).forEach { command ->
            runCatching { runCommand(command) }
        }
        refreshProxySettings()
        backup = null
    }

    private suspend fun readState(): WindowsProxyState {
        return WindowsProxyState(
            proxyEnable = queryValue("ProxyEnable"),
            proxyServer = queryValue("ProxyServer"),
            proxyOverride = queryValue("ProxyOverride"),
            autoConfigUrl = queryValue("AutoConfigURL")
        )
    }

    private suspend fun queryValue(name: String): String? {
        val output = runCatching {
            runCommand(listOf("reg", "query", REGISTRY_KEY, "/v", name))
        }.getOrNull() ?: return null

        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(name) }
            ?.split(Regex("\\s{2,}"))
            ?.lastOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun refreshProxySettings() {
        runCatching { runCommand(refreshCommand()) }
    }

    companion object {
        private const val REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

        fun enableCommands(pacUrl: String): List<List<String>> {
            return listOf(
                setDwordCommand("ProxyEnable", "0"),
                setStringCommand("AutoConfigURL", pacUrl)
            )
        }

        fun restoreCommands(state: WindowsProxyState): List<List<String>> {
            return listOf(
                valueCommand("ProxyEnable", state.proxyEnable, isDword = true),
                valueCommand("ProxyServer", state.proxyServer, isDword = false),
                valueCommand("ProxyOverride", state.proxyOverride, isDword = false),
                valueCommand("AutoConfigURL", state.autoConfigUrl, isDword = false)
            )
        }

        private fun valueCommand(name: String, value: String?, isDword: Boolean): List<String> {
            return if (value == null) {
                listOf("reg", "delete", REGISTRY_KEY, "/v", name, "/f")
            } else if (isDword) {
                setDwordCommand(name, value.removePrefix("0x").toIntOrNull(16)?.toString() ?: value)
            } else {
                setStringCommand(name, value)
            }
        }

        private fun setStringCommand(name: String, value: String): List<String> {
            return listOf("reg", "add", REGISTRY_KEY, "/v", name, "/t", "REG_SZ", "/d", value, "/f")
        }

        private fun setDwordCommand(name: String, value: String): List<String> {
            return listOf("reg", "add", REGISTRY_KEY, "/v", name, "/t", "REG_DWORD", "/d", value, "/f")
        }

        fun refreshCommand(): List<String> {
            val script = """
                ${'$'}signature = '[System.Runtime.InteropServices.DllImport("wininet.dll", SetLastError = true)] public static extern bool InternetSetOption(System.IntPtr hInternet, int dwOption, System.IntPtr lpBuffer, int dwBufferLength);';
                Add-Type -MemberDefinition ${'$'}signature -Name WinInet -Namespace Native;
                [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 39, [System.IntPtr]::Zero, 0) | Out-Null;
                [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 37, [System.IntPtr]::Zero, 0) | Out-Null;
            """.trimIndent()
            return listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
        }
    }
}

internal fun validatePacUrl(pacUrl: String) {
    val uri = runCatching { URI(pacUrl.trim()) }
        .getOrElse { throw IllegalArgumentException("Invalid PAC URL") }
    require(uri.scheme == "http" || uri.scheme == "https") { "PAC URL must use http or https scheme" }
    require(uri.host?.isNotBlank() == true) { "PAC URL must include a host" }
    require(uri.userInfo == null) { "PAC URL must not include credentials" }
    require(uri.fragment == null) { "PAC URL must not include a fragment" }
}

private suspend fun runCommand(command: List<String>): String = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("${command.joinToString(" ")} failed with code $exitCode: $output")
    }
    output
}
