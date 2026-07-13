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
    val dataDir: Path? = null,
    val dnsServer: String
) {
    fun args(configPath: Path): List<String> {
        return listOf(binary.toString(), configPath.toString())
    }

    fun yaml(): String {
        val config = location.normalized()
        val provider = desktopProviderArg(config.bypassProvider)

        return buildString {
            appendLine("mode: cnc")
            appendLine("link: direct")
            appendLine("auth:")
            appendLine("  provider: ${provider.yamlValue()}")
            appendLine("room:")
            appendLine("  id: ${config.id.yamlValue()}")
            appendLine("crypto:")
            appendLine("  key: ${config.key.yamlValue()}")
            appendLine("net:")
            appendLine("  transport: ${config.transport.yamlValue()}")
            appendLine("  dns: ${dnsServer.yamlValue()}")
            if (config.bypassProvider == LocationConfig.PROVIDER_JITSI) {
                appendLine("tls:")
                appendLine("  insecure_skip_verify: true")
                appendLine("jitsi:")
                appendLine("  insecure: true")
            }
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
            appendLine("data: ${(dataDir?.toString() ?: "data").yamlValue()}")
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
