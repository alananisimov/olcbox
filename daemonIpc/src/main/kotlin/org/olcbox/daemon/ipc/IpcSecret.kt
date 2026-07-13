package org.olcbox.daemon.ipc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

// Shared secret bootstrap + validation. The client (unprivileged GUI) creates
// the key file; the daemon (root) can always read it regardless of its 0600
// permissions — that asymmetry is the trust bridge between the two sides.
object IpcSecret {
    const val KEY_FILE_NAME = "ipc.key"
    const val USER_FOLDER_NAME = ".olcbox"

    private const val SECRET_BYTES = 32
    private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

    // Client-side only: create the secret on first run, or return the
    // existing one. Never called by the daemon/install path.
    fun getOrCreateSecret(dir: Path): Path {
        Files.createDirectories(dir)
        val keyFile = dir.resolve(KEY_FILE_NAME)
        if (!Files.exists(keyFile)) {
            val bytes = ByteArray(SECRET_BYTES)
            SecureRandom().nextBytes(bytes)
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(bytes))
            Files.setPosixFilePermissions(keyFile, OWNER_ONLY)
        }
        return keyFile
    }

    fun readSecret(keyFile: Path): String {
        return Files.readString(keyFile).trim()
    }

    // Server-side path validation: the client tells the daemon where its key
    // lives, so the daemon must pin exactly which file it is willing to read
    // on that claim — anything else turns a root process into an
    // attacker-directed arbitrary-file-read primitive. Callers must
    // normalize()/toAbsolutePath() the client-supplied path before calling
    // this, so relative segments can't spoof the filename/parent check.
    fun hasExpectedStructure(keyFile: Path): Boolean {
        val fileName = keyFile.fileName?.toString() ?: return false
        val parentName = keyFile.parent?.fileName?.toString() ?: return false
        return fileName == KEY_FILE_NAME && parentName == USER_FOLDER_NAME
    }

    fun isOwnerOnly(path: Path): Boolean {
        if (!Files.exists(path) || !Files.isRegularFile(path)) return false
        return try {
            Files.getPosixFilePermissions(path) == OWNER_ONLY
        } catch (_: Exception) {
            false
        }
    }
}
