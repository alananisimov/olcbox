package org.olcbox.app.vpn

/**
 * iOS connection modes, mirroring Android's `AndroidConnectionMode`.
 *
 * - [Proxy]: olcRTC runs in-app and exposes a local SOCKS5 endpoint only. Kept alive
 *   in the background by the silent-audio hack. Nothing is system-wide.
 * - [Tun]: a `NEPacketTunnelProvider` extension captures all device traffic and routes
 *   it through the olcRTC SOCKS5 (hosted inside the extension). A real VPN.
 */
enum class IosConnectionMode(val value: String) {
    Proxy("proxy"),
    Tun("tun");

    companion object {
        fun fromValue(value: String?): IosConnectionMode =
            entries.firstOrNull { it.value == value } ?: Proxy
    }
}
