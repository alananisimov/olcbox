package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path

class JvmDesktopConnectionModeStore(
    private val file: Path = DesktopPaths.appDataDir().resolve("desktop_connection_mode.json")
) {
    suspend fun load(): DesktopConnectionMode? {
        return runCatching {
            if (!Files.exists(file)) return null
            json.decodeFromString(DesktopConnectionMode.serializer(), Files.readString(file))
        }.getOrNull()
    }

    suspend fun save(mode: DesktopConnectionMode) {
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(DesktopConnectionMode.serializer(), mode))
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
