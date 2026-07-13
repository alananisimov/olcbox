package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.olcRtcNativeLibrarySpec
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
        server.start("127.0.0.1", 10810, "user", "pass")

        val pac = server.currentPacContent()
        assertContains(pac, "SOCKS5 user:pass@127.0.0.1:10810; SOCKS user:pass@127.0.0.1:10810")
        assertTrue("SOCKS5 127.0.0.1:10808" !in pac)

        server.stop()
    }

    @Test
    fun pacEscapesSocksCredentialsInUserInfo() {
        val pac = PacServer.generatePac(
            socksHost = "127.0.0.1",
            socksPort = 10808,
            socksUsername = "user name",
            socksPassword = "p@ss:word"
        )

        assertContains(
            pac,
            "SOCKS5 user%20name:p%40ss%3Aword@127.0.0.1:10808; " +
                    "SOCKS user%20name:p%40ss%3Aword@127.0.0.1:10808"
        )
    }

    @Test
    fun olcRtcCommandUsesLocationProviderRoomAndKey() {
        LocationConfig.supportedBypassProviders.forEach { provider ->
            val expectedTransport = LocationConfig.normalizeTransport(
                LocationConfig.DEFAULT_TRANSPORT,
                provider
            )
            val command = OlcRtcCommand(
                binary = Path.of("/tmp/olcrtc"),
                location = LocationConfig("Test", "room-$provider", "b".repeat(64), provider),
                socksHost = "127.0.0.1",
                socksPort = 10808,
                dnsServer = "1.1.1.1:53"
            )
            val args = command.args(Path.of("/tmp/client.yaml"))
            val yaml = command.yaml()

            assertEquals(listOf("/tmp/olcrtc", "/tmp/client.yaml"), args)
            assertContains(yaml, "mode: cnc")
            assertContains(yaml, "provider: '${OlcRtcCommand.desktopProviderArg(provider)}'")
            assertContains(yaml, "transport: '$expectedTransport'")
            assertContains(yaml, "id: 'room-$provider'")
            assertContains(yaml, "port: 10808")
            if (LocationConfig.normalizeProvider(provider) == LocationConfig.PROVIDER_JITSI) {
                assertContains(yaml, "tls:")
                assertContains(yaml, "insecure_skip_verify: true")
                assertContains(yaml, "jitsi:")
                assertContains(yaml, "insecure: true")
            } else {
                assertTrue("insecure_skip_verify" !in yaml)
                assertTrue("jitsi:" !in yaml)
            }
            if (expectedTransport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                assertContains(yaml, "vp8:")
                assertContains(yaml, "fps: 60")
                assertContains(yaml, "batch_size: 64")
            }
            assertTrue("client-id" !in yaml)
        }
    }

    @Test
    fun olcRtcCommandAllowsDatachannelForNonTelemostProviders() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "WB",
                id = "room-wb",
                key = "b".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            ),
            dataDir = Path.of("/tmp/olcbox-data"),
            dnsServer = "1.1.1.1:53"
        ).yaml()

        assertContains(command, "transport: '${LocationConfig.TRANSPORT_DATACHANNEL}'")
        assertTrue("vp8:" !in command)
        assertContains(command, "data: '/tmp/olcbox-data'")
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
            dnsServer = "1.1.1.1:53"
        ).yaml()

        assertContains(command, "transport: '${LocationConfig.TRANSPORT_SEICHANNEL}'")
        assertContains(command, "sei:")
        assertContains(command, "fps: 60")
        assertContains(command, "batch_size: 64")
        assertContains(command, "fragment_size: 900")
        assertContains(command, "ack_timeout_ms: 2000")
        assertTrue("vp8:" !in command)
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
                dnsServer = "1.1.1.1:53"
            ).yaml()

            assertContains(command, "provider: 'wbstream'")
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
    fun windowsProxyCommandsBackupShapeIsRestorable() {
        val enable = WindowsProxyController.enableCommands("http://127.0.0.1:10809/proxy.pac")
        assertEquals("reg", enable.first().first())
        assertContains(enable.flatten(), "AutoConfigURL")
        assertContains(enable.flatten(), "http://127.0.0.1:10809/proxy.pac")

        val restore = WindowsProxyController.restoreCommands(
            WindowsProxyState(
                proxyEnable = "0x1",
                proxyServer = "127.0.0.1:8888",
                proxyOverride = "<local>",
                autoConfigUrl = null
            )
        )

        assertContains(restore.flatten(), "ProxyEnable")
        assertContains(restore.flatten(), "ProxyServer")
        assertContains(restore.flatten(), "ProxyOverride")
        assertContains(restore.flatten(), "AutoConfigURL")
        assertContains(restore.flatten(), "delete")
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
    fun windowsTunCommandUsesTun2SocksWintunAndLocalSocks() {
        val command = WindowsTunController.tun2SocksCommand(
            tun2SocksBinary = Path.of("C:/Olcbox/bin/tun2socks-windows-amd64.exe"),
            socksPort = 10812
        )

        assertContains(command, "C:/Olcbox/bin/tun2socks-windows-amd64.exe")
        assertContains(command, "--device")
        assertContains(command, "Olcbox")
        assertContains(command, "--proxy")
        assertContains(command, "socks5://127.0.0.1:10812")
        assertContains(command, "--mtu")
        assertContains(command, "1500")
    }

    @Test
    fun windowsTunAdministratorRestartUsesRunAsAndPreservesArguments() {
        val script = WindowsTunController.restartAsAdministratorScript(
            command = "C:/Olc's/Olcbox.exe",
            arguments = listOf("--flag", "C:/Path With Space/data"),
            workingDirectory = "C:/Olcbox Data"
        )

        assertContains(script, "FilePath = 'C:/Olc''s/Olcbox.exe'")
        assertContains(script, "Verb = 'RunAs'")
        assertContains(script, "ArgumentList = '--flag \"C:/Path With Space/data\"'")
        assertContains(script, "WorkingDirectory = 'C:/Olcbox Data'")
        assertContains(script, "Start-Process @startArgs")
    }

}
