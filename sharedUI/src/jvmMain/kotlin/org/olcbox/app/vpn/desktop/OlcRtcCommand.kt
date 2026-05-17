package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

internal data class OlcRtcCommand(
    val binary: Path,
    val location: LocationConfig,
    val socksHost: String = PacServer.LOCAL_SOCKS_HOST,
    val socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
    val socksUser: String = "",
    val socksPass: String = "",
    val dataDir: Path? = null,
    val configFile: Path = Path("olcrtc-client.yaml")
) {
    fun args(): List<String> {
        return listOf(binary.toString(), configFile.toString())
    }

    fun writeConfigFile() {
        configFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(configFile, configYaml())
    }

    fun configYaml(): String {
        val config = location.normalized()
        val provider = desktopProviderArg(config.bypassProvider)
        val dataPath = dataDir ?: configFile.parent ?: Path(".")
        val builder = StringBuilder()

        builder.appendLine("mode: cnc")
        builder.appendLine("link: direct")
        builder.appendLine("auth:")
        builder.appendLine("  provider: $provider")
        builder.appendLine("room:")
        builder.appendLine("  id: ${yamlScalar(config.id)}")
        builder.appendLine("crypto:")
        builder.appendLine("  key: ${yamlScalar(config.key)}")
        builder.appendLine("net:")
        builder.appendLine("  transport: ${config.transport}")
        builder.appendLine("  dns: '1.1.1.1:53'")
        builder.appendLine("socks:")
        builder.appendLine("  host: ${yamlScalar(socksHost)}")
        builder.appendLine("  port: $socksPort")
        builder.appendLine("  user: ${yamlScalar(socksUser)}")
        builder.appendLine("  pass: ${yamlScalar(socksPass)}")
        builder.appendLine("liveness:")
        builder.appendLine("  interval: ${DESKTOP_LIVENESS_INTERVAL_SECONDS}s")
        builder.appendLine("  timeout: ${DESKTOP_LIVENESS_TIMEOUT_SECONDS}s")
        builder.appendLine("  failures: $DESKTOP_LIVENESS_FAILURES")

        when (config.transport) {
            LocationConfig.TRANSPORT_VP8CHANNEL -> {
                builder.appendLine("vp8:")
                builder.appendLine("  fps: ${config.vp8Fps}")
                builder.appendLine("  batch_size: ${config.vp8Batch}")
            }
            LocationConfig.TRANSPORT_SEICHANNEL -> {
                builder.appendLine("sei:")
                builder.appendLine("  fps: 60")
                builder.appendLine("  batch_size: 64")
                builder.appendLine("  fragment_size: 900")
                builder.appendLine("  ack_timeout_ms: 2000")
            }
        }

        builder.appendLine("data: ${yamlScalar(dataPath.toString())}")
        builder.appendLine("debug: false")
        return builder.toString()
    }

    private fun yamlScalar(value: String): String {
        return "'${value.replace("'", "''")}'"
    }

    companion object {
        const val DESKTOP_LIVENESS_INTERVAL_SECONDS = 30
        const val DESKTOP_LIVENESS_TIMEOUT_SECONDS = 15
        const val DESKTOP_LIVENESS_FAILURES = 6

        fun desktopProviderArg(provider: String): String {
            val normalizedProvider = LocationConfig.normalizeProvider(provider)
            return when (normalizedProvider) {
                LocationConfig.PROVIDER_WB_STREAM -> "wbstream"
                else -> normalizedProvider
            }
        }
    }
}
