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
            val command = OlcRtcCommand(
                binary = binary,
                location = LocationConfig("Test", "room-$provider", "b".repeat(64), provider),
                socksHost = "127.0.0.1",
                socksPort = 10808
            ).args()

            assertEquals(binary.toString(), command[0])
            assertEquals(listOf("-mode", "cnc"), command.slice(1..2))
            assertContains(command, "-transport")
            val expectedTransport = LocationConfig.defaultTransportForProvider(provider)
            assertContains(command, expectedTransport)
            if (expectedTransport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                assertContains(command, "-vp8-fps")
                assertContains(command, "60")
                assertContains(command, "-vp8-batch")
                assertContains(command, "64")
            } else {
                assertTrue("-vp8-fps" !in command)
                assertTrue("-vp8-batch" !in command)
            }
            assertEquals(OlcRtcCommand.desktopProviderArg(provider), command[command.indexOf("-carrier") + 1])
            assertEquals(LocationConfig.DEFAULT_CLIENT_ID, command[command.indexOf("-client-id") + 1])
            assertContains(command, "room-$provider")
            assertContains(command, "10808")
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
            dataDir = dataDir
        ).args()

        assertContains(command, LocationConfig.TRANSPORT_DATACHANNEL)
        assertTrue("-vp8-fps" !in command)
        assertEquals(dataDir.toString(), command[command.indexOf("-data") + 1])
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
            )
        ).args()

        assertContains(command, LocationConfig.TRANSPORT_SEICHANNEL)
        assertEquals("60", command[command.indexOf("-fps") + 1])
        assertEquals("64", command[command.indexOf("-batch") + 1])
        assertEquals("900", command[command.indexOf("-frag") + 1])
        assertEquals("2000", command[command.indexOf("-ack-ms") + 1])
        assertTrue("-vp8-fps" !in command)
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
                )
            ).args()

            assertEquals("wbstream", command[command.indexOf("-carrier") + 1])
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
