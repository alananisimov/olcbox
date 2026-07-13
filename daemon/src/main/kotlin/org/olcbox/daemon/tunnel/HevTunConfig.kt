package org.olcbox.daemon.tunnel

// Ported from sharedUI's LinuxTunController.kt companion object — the daemon
// now owns this directly instead of the (unprivileged) GUI shelling out to
// pkexec per toggle. Content is unchanged from the original except:
// post-up/pre-down scripts are no longer optional (the daemon always writes
// both), and downScriptContent() gained an explicit `ip link delete` — part
// of the device-busy fix, see TunnelSupervisor.waitForInterfaceRemoved().
internal object HevTunConfig {
    const val TUN_NAME = "olcbox0"
    const val TUN_IPV4_ADDRESS = "10.0.88.88"
    const val SOCKS_HOST = "127.0.0.1"
    const val MAPDNS_ADDRESS = "198.18.0.2"
    const val MAPDNS_NETWORK = "100.64.0.0"
    const val MAPDNS_NETMASK = "255.192.0.0"
    const val ROUTE_TABLE = "51820"
    const val ROOT_BYPASS_RULE_PREF = "10"
    const val TUN_RULE_PREF = "20"

    fun configContent(socksPort: Int, socksHost: String, postUpScript: String, preDownScript: String): String {
        return buildString {
            appendLine("tunnel:")
            appendLine("  name: $TUN_NAME")
            appendLine("  multi-queue: false")
            appendLine("  ipv4: $TUN_IPV4_ADDRESS")
            appendLine("  post-up-script: $postUpScript")
            appendLine("  pre-down-script: $preDownScript")
            appendLine()
            appendLine("socks5:")
            appendLine("  address: $socksHost")
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

    fun upScriptContent(): String {
        return """
            #!/bin/sh
            set -eu
            ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
            ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
            ip route flush table $ROUTE_TABLE 2>/dev/null || true
            sysctl -w net.ipv4.conf.all.rp_filter=0 || echo "WARN: failed to disable rp_filter (all)"
            sysctl -w net.ipv4.conf.$TUN_NAME.rp_filter=0 || echo "WARN: failed to disable rp_filter ($TUN_NAME)"
            ip link set $TUN_NAME up
            ip rule add uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF
            ip route add default dev $TUN_NAME table $ROUTE_TABLE
            ip rule add lookup $ROUTE_TABLE pref $TUN_RULE_PREF
            if command -v resolvectl >/dev/null 2>&1; then
              resolvectl dns $TUN_NAME $MAPDNS_ADDRESS || echo "WARN: resolvectl dns failed"
              resolvectl domain $TUN_NAME '~.' || echo "WARN: resolvectl domain failed"
              resolvectl default-route $TUN_NAME yes || echo "WARN: resolvectl default-route failed"
            fi
        """.trimIndent()
    }

    fun downScriptContent(): String {
        return """
            #!/bin/sh
            ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
            ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
            ip route flush table $ROUTE_TABLE 2>/dev/null || true
            ip link delete $TUN_NAME 2>/dev/null || true
            if command -v resolvectl >/dev/null 2>&1; then
              resolvectl revert $TUN_NAME || echo "WARN: resolvectl revert failed"
            fi
        """.trimIndent()
    }
}
