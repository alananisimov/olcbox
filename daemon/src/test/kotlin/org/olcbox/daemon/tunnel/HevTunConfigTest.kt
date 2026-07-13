package org.olcbox.daemon.tunnel

import kotlin.test.Test
import kotlin.test.assertContains

// Ported from sharedUI's DesktopProxyModeTest.kt — LinuxTunController's
// static config/script generation moved here wholesale when Linux TUN
// ownership moved from the (deleted) pkexec-per-toggle GUI path to the
// daemon.
class HevTunConfigTest {

    @Test
    fun configContentCanRunRouteScriptsInsidePrivilegedTunnelProcess() {
        val config = HevTunConfig.configContent(
            socksPort = 10810,
            socksHost = "127.0.0.1",
            postUpScript = "/tmp/olcbox-up.sh",
            preDownScript = "/tmp/olcbox-down.sh"
        )

        assertContains(config, "port: 10810")
        assertContains(config, "post-up-script: /tmp/olcbox-up.sh")
        assertContains(config, "pre-down-script: /tmp/olcbox-down.sh")
    }

    @Test
    fun configContentUsesLocalSocksAndIpv4MapDns() {
        val config = HevTunConfig.configContent(
            socksPort = 10808,
            socksHost = HevTunConfig.SOCKS_HOST,
            postUpScript = "/tmp/up.sh",
            preDownScript = "/tmp/down.sh"
        )

        assertContains(config, "name: olcbox0")
        assertContains(config, "ipv4: 10.0.88.88")
        assertContains(config, "address: 127.0.0.1")
        assertContains(config, "port: 10808")
        assertContains(config, "udp: 'tcp'")
        assertContains(config, "mapdns:")
        assertContains(config, "network: 100.64.0.0")
    }

    @Test
    fun scriptsRouteUserTrafficThroughTunAndKeepRootDirect() {
        val up = HevTunConfig.upScriptContent()
        val down = HevTunConfig.downScriptContent()

        assertContains(up, "ip rule add uidrange 0-0 lookup main pref 10")
        assertContains(up, "ip route add default dev olcbox0 table 51820")
        assertContains(up, "ip rule add lookup 51820 pref 20")
        assertContains(up, "resolvectl dns olcbox0 198.18.0.2")
        assertContains(down, "ip rule del uidrange 0-0 lookup main pref 10")
        assertContains(down, "ip route flush table 51820")
        assertContains(down, "ip link delete olcbox0")
        assertContains(down, "resolvectl revert olcbox0")
    }
}
