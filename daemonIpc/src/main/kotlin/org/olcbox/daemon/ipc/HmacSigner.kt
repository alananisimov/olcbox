package org.olcbox.daemon.ipc

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Single sign() function used by both the client (to produce a request
// signature) and the server (to verify it) — the two sides can never drift
// out of sync on the exact bytes being signed.
object HmacSigner {
    private const val ALGORITHM = "HmacSHA256"

    fun sign(secret: String, timestamp: String, body: ByteArray): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
        mac.update(timestamp.toByteArray(Charsets.UTF_8))
        return mac.doFinal(body).toHex()
    }

    fun verify(secret: String, timestamp: String, body: ByteArray, signature: String): Boolean {
        val expected = sign(secret, timestamp, body)
        return constantTimeEquals(expected, signature)
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            builder.append("%02x".format(byte))
        }
        return builder.toString()
    }

    // Avoids leaking timing information about how many leading characters
    // of the signature matched — a plain `==` short-circuits on first
    // mismatch and is a known side channel for secret comparison.
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
