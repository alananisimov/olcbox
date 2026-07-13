package org.olcbox.app.vpn

import kotlinx.serialization.Serializable

@Serializable
enum class DesktopConnectionMode {
    Tun,
    Proxy
}
