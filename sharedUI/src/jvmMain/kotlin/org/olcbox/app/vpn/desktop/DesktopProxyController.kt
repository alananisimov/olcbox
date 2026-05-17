package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths

internal interface DesktopProxyController {
    suspend fun enable(
        pacUrl: String,
        socksHost: String = PacServer.LOCAL_SOCKS_HOST,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
        httpProxyHost: String = HttpConnectProxy.HTTP_PROXY_HOST,
        httpProxyPort: Int = HttpConnectProxy.HTTP_PROXY_PORT
    )
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
    override suspend fun enable(
        pacUrl: String,
        socksHost: String,
        socksPort: Int,
        httpProxyHost: String,
        httpProxyPort: Int
    ) {
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

    override suspend fun enable(
        pacUrl: String,
        socksHost: String,
        socksPort: Int,
        httpProxyHost: String,
        httpProxyPort: Int
    ) {
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
    val autoConfigUrl: String?,
    val winHttp: WindowsWinHttpProxyState = WindowsWinHttpProxyState.Unknown
)

internal sealed interface WindowsWinHttpProxyState {
    data object Direct : WindowsWinHttpProxyState
    data class Proxy(
        val proxyServer: String,
        val bypassList: String?
    ) : WindowsWinHttpProxyState
    data object Unknown : WindowsWinHttpProxyState
}

internal class WindowsProxyController : DesktopProxyController {
    private var backup: WindowsProxyState? = null

    override suspend fun enable(
        pacUrl: String,
        socksHost: String,
        socksPort: Int,
        httpProxyHost: String,
        httpProxyPort: Int
    ) {
        backup = readState()
        enableCommands(
            socksHost = socksHost,
            socksPort = socksPort,
            httpProxyHost = httpProxyHost,
            httpProxyPort = httpProxyPort,
            removeAutoConfigUrl = backup?.autoConfigUrl != null
        )
            .forEach { command ->
                if (command.isWinHttpCommand()) {
                    runCatching { runCommand(command) }
                } else {
                    runCommand(command)
                }
            }
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
            autoConfigUrl = queryValue("AutoConfigURL"),
            winHttp = readWinHttpState()
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

    private suspend fun readWinHttpState(): WindowsWinHttpProxyState {
        val output = runCatching {
            runCommand(listOf("netsh", "winhttp", "dump"))
        }.getOrNull() ?: return WindowsWinHttpProxyState.Unknown

        return parseWinHttpDump(output)
    }

    private suspend fun refreshProxySettings() {
        runCatching { runCommand(refreshCommand()) }
    }

    companion object {
        private const val REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

        fun enableCommands(
            socksHost: String,
            socksPort: Int,
            httpProxyHost: String = HttpConnectProxy.HTTP_PROXY_HOST,
            httpProxyPort: Int = HttpConnectProxy.HTTP_PROXY_PORT,
            removeAutoConfigUrl: Boolean = true
        ): List<List<String>> {
            return buildList {
                add(
                    setDwordCommand("ProxyEnable", "1")
                )
                add(
                    setStringCommand(
                        "ProxyServer",
                        "$httpProxyHost:$httpProxyPort"
                    )
                )
                add(
                    setStringCommand("ProxyOverride", "<local>;localhost;127.*")
                )
                if (removeAutoConfigUrl) {
                    add(listOf("reg", "delete", REGISTRY_KEY, "/v", "AutoConfigURL", "/f"))
                }
                add(winHttpSetProxyCommand(httpProxyHost, httpProxyPort))
            }
        }

        fun restoreCommands(state: WindowsProxyState): List<List<String>> {
            return listOfNotNull(
                valueCommand("ProxyEnable", state.proxyEnable, isDword = true),
                valueCommand("ProxyServer", state.proxyServer, isDword = false),
                valueCommand("ProxyOverride", state.proxyOverride, isDword = false),
                valueCommand("AutoConfigURL", state.autoConfigUrl, isDword = false),
                winHttpRestoreCommand(state.winHttp)
            )
        }

        fun parseWinHttpDump(output: String): WindowsWinHttpProxyState {
            val setProxyLine = output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("set proxy", ignoreCase = true) }

            if (setProxyLine == null) {
                return if (output.contains("reset proxy", ignoreCase = true)) {
                    WindowsWinHttpProxyState.Direct
                } else {
                    WindowsWinHttpProxyState.Unknown
                }
            }

            val proxyServer = extractNetshValue(setProxyLine, "proxy-server")
                ?: return WindowsWinHttpProxyState.Unknown
            val bypassList = extractNetshValue(setProxyLine, "bypass-list")

            return WindowsWinHttpProxyState.Proxy(
                proxyServer = proxyServer,
                bypassList = bypassList
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

        private fun winHttpSetProxyCommand(host: String, port: Int): List<String> {
            return listOf(
                "netsh",
                "winhttp",
                "set",
                "proxy",
                "proxy-server=$host:$port",
                "bypass-list=<local>;localhost;127.*"
            )
        }

        private fun winHttpRestoreCommand(state: WindowsWinHttpProxyState): List<String>? {
            return when (state) {
                WindowsWinHttpProxyState.Direct -> listOf("netsh", "winhttp", "reset", "proxy")
                is WindowsWinHttpProxyState.Proxy -> buildList {
                    add("netsh")
                    add("winhttp")
                    add("set")
                    add("proxy")
                    add("proxy-server=${state.proxyServer}")
                    state.bypassList?.takeIf { it.isNotBlank() }?.let {
                        add("bypass-list=$it")
                    }
                }
                WindowsWinHttpProxyState.Unknown -> null
            }
        }

        private fun extractNetshValue(line: String, key: String): String? {
            val match = Regex("""\b$key=(?:"([^"]*)"|(\S+))""", RegexOption.IGNORE_CASE).find(line)
                ?: return null
            return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
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

private fun List<String>.isWinHttpCommand(): Boolean {
    return size >= 2 && this[0].equals("netsh", ignoreCase = true) && this[1].equals("winhttp", ignoreCase = true)
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
