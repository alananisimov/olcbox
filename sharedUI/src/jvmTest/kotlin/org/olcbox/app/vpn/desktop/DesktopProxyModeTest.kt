package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.olcRtcNativeLibrarySpec
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopProxyModeTest {

    @Test
    fun pacRoutesLocalTrafficDirectAndEverythingElseThroughSocks() {
        val pac = PacServer.generatePac("127.0.0.1", 10808)

        assertContains(pac, "isPlainHostName(host)")
        assertContains(pac, "host == \"localhost\"")
        assertContains(pac, "SOCKS5 127.0.0.1:10808; SOCKS 127.0.0.1:10808")
    }

    @Test
    fun pacServerUpdatesSocksTargetWhileAlreadyRunning() {
        val server = PacServer(port = 0)

        server.start("127.0.0.1", 10808)
        server.start("127.0.0.1", 10810)

        val pac = server.currentPacContent()
        assertContains(pac, "SOCKS5 127.0.0.1:10810; SOCKS 127.0.0.1:10810")
        assertTrue("SOCKS5 127.0.0.1:10808" !in pac)

        server.stop()
    }

    @Test
    fun olcRtcCommandUsesLocationProviderRoomAndKey() {
        LocationConfig.supportedBypassProviders.forEach { provider ->
            val binary = Path.of("/tmp/olcrtc")
            val configFile = Path.of("/tmp/olcbox-client.yaml")
            val command = OlcRtcCommand(
                binary = binary,
                location = LocationConfig("Test", "room-$provider", "b".repeat(64), provider),
                socksHost = "127.0.0.1",
                socksPort = 10808,
                configFile = configFile
            ).args()

            assertEquals(listOf(binary.toString(), configFile.toString()), command)
        }
    }

    @Test
    fun olcRtcCommandWritesUniversalCarrierYamlConfig() {
        val dataDir = Path.of("/tmp/olcbox-data")
        val configFile = Path.of("/tmp/olcbox-client.yaml")
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "Jitsi",
                id = "https://meet.cryptopro.ru/room-one",
                key = "b".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_JITSI,
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            ),
            socksHost = "127.0.0.1",
            socksPort = 10808,
            socksUser = "user",
            socksPass = "pass",
            dataDir = dataDir,
            configFile = configFile
        )

        val yaml = command.configYaml()

        assertContains(yaml, "mode: cnc")
        assertContains(yaml, "link: direct")
        assertContains(yaml, "provider: jitsi")
        assertContains(yaml, "id: 'https://meet.cryptopro.ru/room-one'")
        assertContains(yaml, "key: '${"b".repeat(64)}'")
        assertContains(yaml, "transport: datachannel")
        assertContains(yaml, "dns: '1.1.1.1:53'")
        assertContains(yaml, "host: '127.0.0.1'")
        assertContains(yaml, "port: 10808")
        assertContains(yaml, "user: 'user'")
        assertContains(yaml, "pass: 'pass'")
        assertContains(yaml, "interval: 30s")
        assertContains(yaml, "timeout: 15s")
        assertContains(yaml, "failures: 6")
        assertContains(yaml, "data: '${dataDir}'")
    }

    @Test
    fun olcRtcCommandDefaultsJitsiToDatachannelInYaml() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "Jitsi",
                id = "https://meet.cryptopro.ru/room-one",
                key = "b".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_JITSI,
                transport = ""
            ),
            configFile = Path.of("/tmp/olcbox-client.yaml")
        )

        val yaml = command.configYaml()

        assertContains(yaml, "provider: jitsi")
        assertContains(yaml, "transport: datachannel")
        assertTrue("vp8:" !in yaml)
    }

    @Test
    fun olcRtcCommandAddsTransportSpecificYamlOnlyWhenNeeded() {
        LocationConfig.supportedBypassProviders.forEach { provider ->
            val command = OlcRtcCommand(
                binary = Path.of("/tmp/olcrtc"),
                location = LocationConfig("Test", "room-$provider", "b".repeat(64), provider),
                configFile = Path.of("/tmp/olcbox-client.yaml")
            )

            val yaml = command.configYaml()
            val expectedTransport = LocationConfig.defaultTransportForProvider(provider)
            assertContains(yaml, "transport: $expectedTransport")
            if (expectedTransport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                assertContains(yaml, "vp8:")
                assertContains(yaml, "fps: 60")
                assertContains(yaml, "batch_size: 64")
            } else {
                assertTrue("vp8:" !in yaml)
            }
        }
    }

    @Test
    fun olcRtcCommandAllowsDatachannelForNonTelemostProviders() {
        val dataDir = Path.of("/tmp/olcbox-data")
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "WB",
                id = "room-wb",
                key = "b".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            ),
            dataDir = dataDir,
            configFile = Path.of("/tmp/olcbox-client.yaml")
        ).args()

        assertEquals(listOf(Path.of("/tmp/olcrtc").toString(), Path.of("/tmp/olcbox-client.yaml").toString()), command)
    }

    @Test
    fun olcRtcCommandAddsSeiDefaults() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "Telemost",
                id = "room",
                key = "c".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_TELEMOST,
                transport = LocationConfig.TRANSPORT_SEICHANNEL
            ),
            configFile = Path.of("/tmp/olcbox-client.yaml")
        )

        val yaml = command.configYaml()

        assertContains(yaml, "transport: seichannel")
        assertContains(yaml, "sei:")
        assertContains(yaml, "fps: 60")
        assertContains(yaml, "batch_size: 64")
        assertContains(yaml, "fragment_size: 900")
        assertContains(yaml, "ack_timeout_ms: 2000")
        assertTrue("vp8:" !in yaml)
    }

    @Test
    fun nativeLibrarySpecSelectsPlatformFiles() {
        assertEquals(
            "libolcrtc-darwin-arm64.dylib",
            olcRtcNativeLibrarySpec("Mac OS X", "aarch64")?.fileName
        )
        assertEquals(
            "libolcrtc-linux-amd64.so",
            olcRtcNativeLibrarySpec("Linux", "x86_64")?.fileName
        )
        assertEquals(
            "olcrtc-windows-amd64.dll",
            olcRtcNativeLibrarySpec("Windows 11", "amd64")?.fileName
        )
    }

    @Test
    fun linuxTunConfigCanRunRouteScriptsInsidePrivilegedTunnelProcess() {
        val config = LinuxTunController.configContent(
            socksPort = 10810,
            postUpScript = "/tmp/olcbox-up.sh",
            preDownScript = "/tmp/olcbox-down.sh"
        )

        assertContains(config, "port: 10810")
        assertContains(config, "post-up-script: /tmp/olcbox-up.sh")
        assertContains(config, "pre-down-script: /tmp/olcbox-down.sh")
    }

    @Test
    fun olcRtcCommandUsesDesktopWbStreamProviderAlias() {
        listOf(LocationConfig.PROVIDER_WB_STREAM, "wbstream").forEach { provider ->
            val command = OlcRtcCommand(
                binary = Path.of("/tmp/olcrtc"),
                location = LocationConfig(
                    name = "WB",
                    id = "room-wb",
                    key = "b".repeat(64),
                    bypassProvider = provider
                ),
                configFile = Path.of("/tmp/olcbox-client.yaml")
            ).configYaml()

            assertContains(command, "provider: wbstream")
        }
    }

    @Test
    fun macOsProxyCommandsEnableAndRestorePacPerService() {
        val enable = MacOsProxyController.enableCommands(listOf("Wi-Fi"), "http://127.0.0.1:10809/proxy.pac")
        assertEquals(
            listOf(
                listOf("networksetup", "-setautoproxyurl", "Wi-Fi", "http://127.0.0.1:10809/proxy.pac"),
                listOf("networksetup", "-setautoproxystate", "Wi-Fi", "on")
            ),
            enable
        )

        val restore = MacOsProxyController.restoreCommands(
            listOf(
                MacOsAutoProxyState("Wi-Fi", enabled = true, url = "http://old/proxy.pac"),
                MacOsAutoProxyState("USB", enabled = false, url = null)
            )
        )
        assertEquals(
            listOf(
                listOf("networksetup", "-setautoproxyurl", "Wi-Fi", "http://old/proxy.pac"),
                listOf("networksetup", "-setautoproxystate", "Wi-Fi", "on"),
                listOf("networksetup", "-setautoproxystate", "USB", "off")
            ),
            restore
        )
    }

    @Test
    fun windowsProxyCommandsUseHttpAndSocksSystemProxy() {
        val enable = WindowsProxyController.enableCommands("127.0.0.1", 10808)
        assertEquals("reg", enable.first().first())
        assertContains(enable.flatten(), "ProxyEnable")
        assertContains(enable.flatten(), "ProxyServer")
        assertContains(enable.flatten(), "http=127.0.0.1:10810;https=127.0.0.1:10810;socks=127.0.0.1:10808")
        assertContains(enable.flatten(), "AutoConfigURL")
        assertContains(enable.flatten(), "delete")
    }

    @Test
    fun windowsProxyCommandsSkipPacDeleteWhenAutoConfigUrlIsAbsent() {
        val enable = WindowsProxyController.enableCommands(
            socksHost = "127.0.0.1",
            socksPort = 10808,
            removeAutoConfigUrl = false
        )

        assertContains(enable.flatten(), "ProxyEnable")
        assertContains(enable.flatten(), "ProxyServer")
        assertFalse(enable.any { command -> command.contains("AutoConfigURL") && command.contains("delete") })
    }

    @Test
    fun windowsProxyCommandsBackupShapeIsRestorable() {
        val restore = WindowsProxyController.restoreCommands(
            WindowsProxyState(
                proxyEnable = "0x1",
                proxyServer = "127.0.0.1:8888",
                proxyOverride = "<local>",
                autoConfigUrl = null,
                winHttp = WindowsWinHttpProxyState.Direct
            )
        )

        assertContains(restore.flatten(), "ProxyEnable")
        assertContains(restore.flatten(), "ProxyServer")
        assertContains(restore.flatten(), "ProxyOverride")
        assertContains(restore.flatten(), "AutoConfigURL")
        assertContains(restore.flatten(), "delete")
    }

    @Test
    fun windowsProxyCommandsSetWinHttpToOlcboxHttpProxy() {
        val enable = WindowsProxyController.enableCommands(
            socksHost = "127.0.0.1",
            socksPort = 10808,
            httpProxyHost = "127.0.0.1",
            httpProxyPort = 10810
        )

        assertTrue(
            enable.any { command ->
                command == listOf(
                    "netsh",
                    "winhttp",
                    "set",
                    "proxy",
                    "proxy-server=http=127.0.0.1:10810;https=127.0.0.1:10810",
                    "bypass-list=<local>;localhost;127.*"
                )
            }
        )
    }

    @Test
    fun windowsProxyCommandsRestoreDirectWinHttpProxy() {
        val restore = WindowsProxyController.restoreCommands(
            WindowsProxyState(
                proxyEnable = "0x1",
                proxyServer = null,
                proxyOverride = null,
                autoConfigUrl = null,
                winHttp = WindowsWinHttpProxyState.Direct
            )
        )

        assertTrue(restore.any { command -> command == listOf("netsh", "winhttp", "reset", "proxy") })
    }

    @Test
    fun windowsProxyCommandsRestorePreviousWinHttpProxy() {
        val restore = WindowsProxyController.restoreCommands(
            WindowsProxyState(
                proxyEnable = "0x1",
                proxyServer = null,
                proxyOverride = null,
                autoConfigUrl = null,
                winHttp = WindowsWinHttpProxyState.Proxy(
                    proxyServer = "http=127.0.0.1:3067;https=127.0.0.1:3067",
                    bypassList = "<local>"
                )
            )
        )

        assertTrue(
            restore.any { command ->
                command == listOf(
                    "netsh",
                    "winhttp",
                    "set",
                    "proxy",
                    "proxy-server=http=127.0.0.1:3067;https=127.0.0.1:3067",
                    "bypass-list=<local>"
                )
            }
        )
    }

    @Test
    fun windowsWinHttpDumpParserKeepsDirectState() {
        val state = WindowsProxyController.parseWinHttpDump(
            """
            # WinHTTP Proxy Configuration
            pushd winhttp
            reset proxy
            popd
            """.trimIndent()
        )

        assertEquals(WindowsWinHttpProxyState.Direct, state)
    }

    @Test
    fun windowsWinHttpDumpParserKeepsProxyState() {
        val state = WindowsProxyController.parseWinHttpDump(
            """
            # WinHTTP Proxy Configuration
            pushd winhttp
            set proxy proxy-server="http=127.0.0.1:3067;https=127.0.0.1:3067" bypass-list="<local>"
            popd
            """.trimIndent()
        )

        assertEquals(
            WindowsWinHttpProxyState.Proxy(
                proxyServer = "http=127.0.0.1:3067;https=127.0.0.1:3067",
                bypassList = "<local>"
            ),
            state
        )
    }

    @Test
    fun windowsProxyRefreshCommandUsesFullyQualifiedWinInetSignature() {
        val refresh = WindowsProxyController.refreshCommand()
        val script = refresh.last()

        assertEquals("powershell.exe", refresh.first())
        assertContains(script, "System.Runtime.InteropServices.DllImport")
        assertContains(script, "System.IntPtr")
        assertContains(script, "InternetSetOption")
    }

    @Test
    fun linuxTunConfigUsesLocalSocksAndIpv4MapDns() {
        val config = LinuxTunController.configContent()

        assertContains(config, "name: olcbox0")
        assertContains(config, "ipv4: 10.0.88.88")
        assertContains(config, "address: 127.0.0.1")
        assertContains(config, "port: 10808")
        assertContains(config, "udp: 'tcp'")
        assertContains(config, "mapdns:")
        assertContains(config, "network: 100.64.0.0")
    }

    @Test
    fun linuxTunScriptsRouteUserTrafficThroughTunAndKeepRootDirect() {
        val up = LinuxTunController.upScriptContent()
        val down = LinuxTunController.downScriptContent()

        assertContains(up, "ip rule add uidrange 0-0 lookup main pref 10")
        assertContains(up, "ip route add default dev olcbox0 table 51820")
        assertContains(up, "ip rule add lookup 51820 pref 20")
        assertContains(up, "resolvectl dns olcbox0 1.1.1.1")
        assertContains(down, "ip rule del uidrange 0-0 lookup main pref 10")
        assertContains(down, "ip route flush table 51820")
        assertContains(down, "resolvectl revert olcbox0")
    }
}
