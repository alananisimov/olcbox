package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.exists

internal object DesktopNativeAssets {
    fun resolveOlcRtcBinary(): Path {
        return resolveOlcRtcBinaryCandidates().first()
    }

    fun resolveOlcRtcBinaryCandidates(): List<Path> {
        val fileNames = olcRtcFileNames()
        return fileNames.mapNotNull { resolveOlcRtcBinaryOrNull(it) }.also {
            if (it.isEmpty()) {
                error("Bundled native binary is missing: ${fileNames.joinToString(", ") { name -> "native/$name" }}")
            }
        }
    }

    private fun resolveOlcRtcBinaryOrNull(fileName: String): Path? {
        return try {
            resolveBinary(
                fileName = fileName,
                resourceName = "native/$fileName",
                candidates = olcRtcSourceCandidates(fileName)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun olcRtcFileNames(): List<String> {
        return when (DesktopPaths.os) {
            DesktopOs.MacOS -> listOf(
                "olcrtc-darwin-${desktopArch()}",
                "olcrtc-darwin-${desktopArchFallback()}"
            ).distinct()
            DesktopOs.Windows -> listOf("olcrtc-windows-amd64.exe")
            DesktopOs.Linux -> listOf("olcrtc-linux-${desktopArch()}")
            DesktopOs.Other -> error("Olcbox desktop supports macOS, Windows and Linux")
        }
    }

    private fun olcRtcFileName(): String = olcRtcFileNames().first()

    private fun desktopArchFallback(): String {
        return when (desktopArch()) {
            "arm64" -> "amd64"
            "amd64" -> "arm64"
            else -> "amd64"
        }
    }

    fun resolveOlcRtcDataDir(): Path {
        val target = DesktopPaths.appDataDir().resolve("olcrtc-data")
        Files.createDirectories(target)
        copyDataFile("names", target)
        copyDataFile("surnames", target)
        return target
    }

    fun resolveWindowsTun2SocksBinary(): Path {
        val fileName = windowsTun2SocksFileName()
        val binary = resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = windowsTun2SocksSourceCandidates(fileName)
        )
        copyRuntimeAsset("wintun.dll")
        return binary
    }

    private fun resolveBinary(
        fileName: String,
        resourceName: String,
        candidates: List<Path>
    ): Path {
        val target = DesktopPaths.appDataDir().resolve("bin").resolve(fileName)
        Files.createDirectories(target.parent)

        val resource = javaClass.classLoader.getResourceAsStream(resourceName)
        if (resource != null) {
            resource.use {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            }
            makeExecutable(target)
            return target
        }

        candidates.firstOrNull { it.exists() }?.let {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(target)
            return target
        }

        error("Bundled native binary is missing: $resourceName")
    }

    private fun copyDataFile(fileName: String, targetDir: Path) {
        val target = targetDir.resolve(fileName)
        val resourceName = "olcrtc-data/$fileName"
        val resource = javaClass.classLoader.getResourceAsStream(resourceName)
        if (resource != null) {
            resource.use {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return
        }

        olcRtcDataSourceCandidates(fileName).firstOrNull { it.exists() }?.let {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            return
        }

        error("Bundled olcRTC data file is missing: $resourceName")
    }

    fun windowsTun2SocksFileName(): String {
        return when (DesktopPaths.os) {
            DesktopOs.Windows -> "tun2socks-windows-amd64.exe"
            else -> error("tun2socks desktop binary is only used for Windows TUN mode")
        }
    }

    private fun desktopArch(): String {
        return when (DesktopPaths.arch) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> error("Unsupported desktop architecture: ${DesktopPaths.arch}")
        }
    }

    private fun olcRtcSourceCandidates(fileName: String): List<Path> {
        val explicitBinary = System.getenv("OLCRTC_BINARY")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        val explicitRepo = System.getenv("OLCRTC_REPO")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        val defaultRepo = Path("..").resolve("olcrtc")
        return listOfNotNull(
            explicitBinary,
            explicitRepo
        ).flatMap { repoOrBinary ->
            if (repoOrBinary.fileName?.toString() == fileName || repoOrBinary.fileName?.toString() == fileName.removeSuffix(".exe")) {
                listOf(repoOrBinary)
            } else {
                repoCandidates(repoOrBinary, fileName)
            }
        } + repoCandidates(defaultRepo, fileName)
    }

    private fun olcRtcDataSourceCandidates(fileName: String): List<Path> {
        val explicitRepo = System.getenv("OLCRTC_REPO")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        val defaultRepo = Path("..").resolve("olcrtc")
        return listOfNotNull(explicitRepo, defaultRepo).map { repo ->
            repo.resolve("data").resolve(fileName)
        }
    }

    private fun repoCandidates(repo: Path, fileName: String): List<Path> {
        return listOf(
            repo.resolve("build").resolve(fileName),
            repo.resolve(fileName.removeSuffix(".exe")),
            repo.resolve("olcrtc")
        )
    }

    private fun windowsTun2SocksSourceCandidates(fileName: String): List<Path> {
        val explicitBinary = System.getenv("TUN2SOCKS_BINARY")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        return listOfNotNull(explicitBinary) + desktopNativeResourceCandidates(fileName)
    }

    private fun copyRuntimeAsset(fileName: String): Path {
        val target = DesktopPaths.appDataDir().resolve("bin").resolve(fileName)
        Files.createDirectories(target.parent)
        val resourceName = "native/$fileName"
        val resource = javaClass.classLoader.getResourceAsStream(resourceName)
        if (resource != null) {
            resource.use {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        }

        desktopNativeResourceCandidates(fileName)
            .firstOrNull { it.exists() }
            ?.let {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                return target
            }

        error("Bundled runtime asset is missing: $resourceName")
    }

    private fun makeExecutable(path: Path) {
        if (DesktopPaths.os != DesktopOs.Windows) {
            path.toFile().setExecutable(true, true)
        }
    }

    private fun desktopNativeResourceCandidates(fileName: String): List<Path> {
        return projectRootCandidates().flatMap { root ->
            listOf(
                root.resolve("desktopApp").resolve("build").resolve("generated")
                    .resolve("desktopNativeResources").resolve("native").resolve(fileName),
                root.resolve("build").resolve("generated").resolve("desktopNativeResources")
                    .resolve("native").resolve(fileName)
            )
        }.distinct()
    }

    private fun projectRootCandidates(): List<Path> {
        val cwd = Path("").toAbsolutePath().normalize()
        return generateSequence(cwd) { it.parent }
            .take(5)
            .toList()
    }
}
