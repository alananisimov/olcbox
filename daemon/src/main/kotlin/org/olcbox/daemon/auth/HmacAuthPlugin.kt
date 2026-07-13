package org.olcbox.daemon.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import org.olcbox.daemon.ipc.HmacHeaders
import org.olcbox.daemon.ipc.HmacSigner
import org.olcbox.daemon.ipc.IpcSecret
import org.olcbox.daemon.routes.HEALTH_PATH

private const val MAX_CLOCK_SKEW_SECONDS = 60L

// Validates every request except /health against the client's HMAC key file.
// The daemon runs as root, so it can always read the client's 0600 key file
// regardless of file ownership — that asymmetry is the trust bridge. The
// filename/parent-dir pinning (IpcSecret.hasExpectedStructure) keeps this
// read confined to exactly one well-known relative path shape, so a
// client-supplied path can never turn the daemon into an
// attacker-directed arbitrary-file-read primitive.
val HmacAuthPlugin = createApplicationPlugin("HmacAuth") {
    onCall { call ->
        if (call.request.path() == HEALTH_PATH) {
            return@onCall
        }

        val keyPathHeader = call.request.header(HmacHeaders.KEY_PATH)
            ?: return@onCall call.respond(HttpStatusCode.Unauthorized, "Missing IPC key path")

        val keyPath: Path = try {
            Paths.get(keyPathHeader).normalize().toAbsolutePath()
        } catch (_: Exception) {
            return@onCall call.respond(HttpStatusCode.Unauthorized, "Invalid key path")
        }

        if (!IpcSecret.hasExpectedStructure(keyPath)) {
            return@onCall call.respond(HttpStatusCode.Unauthorized, "Invalid key path structure")
        }
        if (!IpcSecret.isOwnerOnly(keyPath)) {
            return@onCall call.respond(HttpStatusCode.Unauthorized, "Invalid key file permissions")
        }

        val timestamp = call.request.header(HmacHeaders.TIMESTAMP)
            ?: return@onCall call.respond(HttpStatusCode.Unauthorized, "Missing timestamp")
        val timestampSeconds = timestamp.toLongOrNull()
            ?: return@onCall call.respond(HttpStatusCode.Unauthorized, "Invalid timestamp")
        val nowSeconds = System.currentTimeMillis() / 1000
        if (abs(nowSeconds - timestampSeconds) > MAX_CLOCK_SKEW_SECONDS) {
            return@onCall call.respond(HttpStatusCode.Unauthorized, "Timestamp out of range")
        }

        val signature = call.request.header(HmacHeaders.SIGNATURE)
            ?: return@onCall call.respond(HttpStatusCode.Unauthorized, "Missing signature")

        val secret = try {
            IpcSecret.readSecret(keyPath)
        } catch (_: Exception) {
            return@onCall call.respond(HttpStatusCode.Unauthorized, "Key file not readable")
        }
        if (secret.isBlank()) {
            return@onCall call.respond(HttpStatusCode.Unauthorized, "Empty key file")
        }

        val bodyText = call.receiveText()

        if (!HmacSigner.verify(secret, timestamp, bodyText.toByteArray(Charsets.UTF_8), signature)) {
            return@onCall call.respond(HttpStatusCode.Unauthorized, "Invalid HMAC")
        }
    }
}
