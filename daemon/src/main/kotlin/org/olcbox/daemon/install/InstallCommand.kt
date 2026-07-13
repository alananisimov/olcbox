package org.olcbox.daemon.install

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import org.olcbox.daemon.ipc.DaemonPaths

// Self-install flow: `daemon install` copies this already-built distribution
// (installDist output) into /usr/local/lib/olcbox-daemon/ and registers the
// systemd unit. Never invoked by the agent/build tooling — the user runs it
// themselves with sudo at a terminal, exactly once (or again after a
// rebuild; every step here is idempotent).
internal object InstallCommand {
    private const val SERVICE_NAME = "olcbox-daemon.service"
    private const val SYSTEMD_UNIT_PATH = "/etc/systemd/system/$SERVICE_NAME"

    fun install() {
        requireRoot("install")

        val selfBinDir = resolveSelfBinDir()
        println("Installing olcbox-daemon from $selfBinDir")

        copyTree(selfBinDir, Path.of(DaemonPaths.BIN_DIR))

        val libSrc = selfBinDir.parent.resolve("lib")
        if (Files.isDirectory(libSrc)) {
            copyTree(libSrc, Path.of(DaemonPaths.INSTALL_DIR).resolve("lib"))
        }

        val dataSrc = selfBinDir.parent.resolve("data")
        if (Files.isDirectory(dataSrc)) {
            copyTree(dataSrc, Path.of(DaemonPaths.DATA_DIR))
        }

        writeSystemdUnit()

        runCommand(listOf("systemctl", "daemon-reload"))
        runCommand(listOf("systemctl", "enable", SERVICE_NAME))
        // restart (not just enable --now) so a re-install after a rebuild
        // actually picks up the freshly copied binary/jars, even if the
        // service was already running from a previous install.
        runCommand(listOf("systemctl", "restart", SERVICE_NAME))

        println("Installed and started. Status:")
        runCommandInherit(listOf("systemctl", "is-active", SERVICE_NAME))
        println("Socket: ${DaemonPaths.SOCKET_PATH}")
    }

    fun uninstall() {
        requireRoot("uninstall")

        runCatching { runCommand(listOf("systemctl", "disable", "--now", SERVICE_NAME)) }
        Files.deleteIfExists(Path.of(SYSTEMD_UNIT_PATH))
        runCommand(listOf("systemctl", "daemon-reload"))

        File(DaemonPaths.INSTALL_DIR).deleteRecursively()
        println("olcbox-daemon uninstalled.")
    }

    private fun requireRoot(action: String) {
        val uid = runCommand(listOf("id", "-u")).trim()
        if (uid != "0") {
            val launcher = resolveSelfBinDir().resolve("daemon")
            System.err.println("Run this as root: sudo $launcher $action")
            exitProcess(1)
        }
    }

    // The application-plugin launcher script always lives at
    // <installDist-root>/bin/daemon, with the module's own jar (containing
    // this class) at <installDist-root>/lib/*.jar — resolving from the
    // running jar's own on-disk location, rather than argv[0] or
    // ProcessHandle (which reflect the JVM's own command line, not the
    // wrapping launcher script), works whether invoked via the launcher or
    // directly with `java -jar`.
    private fun resolveSelfBinDir(): Path {
        val codeSource = InstallCommand::class.java.protectionDomain.codeSource
        val jarPath = Path.of(codeSource.location.toURI())
        return jarPath.parent.parent.resolve("bin")
    }

    private fun copyTree(src: Path, dst: Path) {
        Files.createDirectories(dst)
        Files.walk(src).use { stream ->
            stream.forEach { source ->
                val target = dst.resolve(src.relativize(source))
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                    if (Files.isExecutable(source)) {
                        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
                    }
                }
            }
        }
    }

    private fun writeSystemdUnit() {
        val resource = InstallCommand::class.java.classLoader
            .getResourceAsStream("systemd/olcbox-daemon.service")
            ?: error("Bundled systemd unit resource is missing")
        resource.use { input ->
            Files.copy(input, Path.of(SYSTEMD_UNIT_PATH), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun runCommand(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("${command.joinToString(" ")} failed with code $exitCode: $output")
        }
        return output
    }

    private fun runCommandInherit(command: List<String>) {
        val process = ProcessBuilder(command).inheritIO().start()
        process.waitFor(5, TimeUnit.SECONDS)
    }
}
