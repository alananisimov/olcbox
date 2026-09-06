package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import java.nio.file.Path

internal data class OlcRtcCommand(
    val binary: Path,
    val location: LocationConfig,
    val socksHost: String = PacServer.LOCAL_SOCKS_HOST,
    val socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
    val socksUser: String = "",
    val socksPass: String = "",
    val dnsServer: String,
    val dataDir: Path? = null
) {
    fun args(configPath: Path): List<String> {
        return listOf(binary.toString(), configPath.toString())
    }

    fun yaml(): String {
        val config = location.normalized()
        val provider = desktopProviderArg(config.bypassProvider)
        // Always emit a failover `profiles:` config (even for a single room) so
        // olcRTC runs under the supervisor and re-reads this file on every hop.
        // That is what makes the room list DYNAMIC: rooms added later (##rooms)
        // are picked up live, no restart. A single-room location is just a
        // one-profile supervisor that reconnects in place instead of exiting.
        val rooms = config.failoverRooms()

        return buildString {
            appendLine("mode: cnc")
            appendLine("auth:")
            appendLine("  provider: ${provider.yamlValue()}")
            appendLine("crypto:")
            appendLine("  key: ${config.key.yamlValue()}")
            appendLine("net:")
            appendLine("  transport: ${config.transport.yamlValue()}")
            appendLine("  dns: ${dnsServer.yamlValue()}")
            appendLine("socks:")
            appendLine("  host: ${socksHost.yamlValue()}")
            appendLine("  port: $socksPort")
            if (socksUser.isNotBlank()) {
                appendLine("  user: ${socksUser.yamlValue()}")
                appendLine("  pass: ${socksPass.yamlValue()}")
            }
            when (config.transport) {
                LocationConfig.TRANSPORT_VP8CHANNEL -> {
                    appendLine("vp8:")
                    appendLine("  fps: ${config.vp8Fps}")
                    appendLine("  batch_size: ${config.vp8Batch}")
                }
                LocationConfig.TRANSPORT_SEICHANNEL -> {
                    appendLine("sei:")
                    appendLine("  fps: 60")
                    appendLine("  batch_size: 64")
                    appendLine("  fragment_size: 900")
                    appendLine("  ack_timeout_ms: 2000")
                }
            }
            dataDir?.let { appendLine("data: ${it.toString().yamlValue()}") }
            appendLine("liveness:")
            appendLine("  interval: 5s")
            appendLine("  timeout: 8s")
            appendLine("  failures: 2")
            appendLine("profiles:")
            rooms.forEach { room ->
                appendLine("  - name: ${room.yamlValue()}")
                appendLine("    room:")
                appendLine("      id: ${room.yamlValue()}")
            }
            appendLine("failover:")
            appendLine("  retry_delay: 2s")
        }
    }

    companion object {
        fun desktopProviderArg(provider: String): String {
            val normalizedProvider = LocationConfig.normalizeProvider(provider)
            return when (normalizedProvider) {
                LocationConfig.PROVIDER_WB_STREAM -> "wbstream"
                else -> normalizedProvider
            }
        }
    }
}

private fun String.yamlValue(): String {
    return "'${replace("'", "''")}'"
}
