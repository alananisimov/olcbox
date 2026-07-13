package org.olcbox.daemon.auth

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

// Server-side socket permission helper. The socket itself is intentionally
// left world-read-write — connecting to it is not the trust boundary, the
// per-request HMAC check (HmacAuthPlugin) is. Mirrors the key-file owner-only
// check in :daemonIpc's IpcSecret, just for the opposite (open) permission.
object PermissionsHelper {
    private val WORLD_READ_WRITE = PosixFilePermissions.fromString("rw-rw-rw-")

    // The CIO engine creates the socket file asynchronously after
    // embeddedServer(...).start(wait = false) returns, so this polls for it
    // to appear before chmod'ing — mirrors wgtunnel's own retry loop for the
    // same reason.
    fun waitAndOpenSocketPermissions(socketFile: File, timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (socketFile.exists()) {
                runCatching {
                    Files.setPosixFilePermissions(socketFile.toPath(), WORLD_READ_WRITE)
                }
                return
            }
            Thread.sleep(50)
        }
    }
}
