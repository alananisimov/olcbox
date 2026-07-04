package org.olcbox.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopPaths
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.outputStream

class JvmUpdateInstaller(
    private val directory: Path = DesktopPaths.appDataDir().resolve("updates"),
    private val proxyProvider: () -> SubscriptionFetchProxy? = { null }
) {
    suspend fun downloadAndOpen(
        asset: AppUpdateAsset,
        onProgress: (Float) -> Unit = {}
    ): Result<String> = runCatching {
        val proxy = proxyProvider()
        val file = withProxyAuthentication(proxy) {
            download(asset, onProgress, proxy)
        }
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        when {
            desktop?.isSupported(Desktop.Action.OPEN) == true -> desktop.open(file.toFile())
            desktop?.isSupported(Desktop.Action.BROWSE) == true -> desktop.browse(URI(asset.downloadUrl))
            else -> error("No system file handler available for ${asset.name}")
        }
        "Opening ${asset.name}"
    }

    private suspend fun download(
        asset: AppUpdateAsset,
        onProgress: (Float) -> Unit,
        proxy: SubscriptionFetchProxy?
    ): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val target = directory.resolve(asset.name.substringAfterLast('/').ifBlank { "olcbox-update" })
        val connection = if (proxy == null) {
            URL(asset.downloadUrl).openConnection()
        } else {
            URL(asset.downloadUrl).openConnection(
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port))
            )
        } as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        val total = connection.contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes ?: -1L
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0L) {
                        reportProgress(
                            (copied.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f),
                            onProgress
                        )
                    }
                }
            }
        }
        reportProgress(1f, onProgress)
        target
    }

    private suspend fun reportProgress(progress: Float, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(progress)
        }
    }
}
