package org.olcbox.daemon.ipc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HmacSignerTest {

    @Test
    fun signAndVerifyRoundTrips() {
        val secret = "test-secret"
        val timestamp = "1720000000"
        val body = """{"locationId":"room-1"}""".toByteArray()

        val signature = HmacSigner.sign(secret, timestamp, body)

        assertTrue(HmacSigner.verify(secret, timestamp, body, signature))
    }

    @Test
    fun verifyRejectsTamperedBody() {
        val secret = "test-secret"
        val timestamp = "1720000000"
        val signature = HmacSigner.sign(secret, timestamp, """{"a":1}""".toByteArray())

        assertFalse(HmacSigner.verify(secret, timestamp, """{"a":2}""".toByteArray(), signature))
    }

    @Test
    fun verifyRejectsTamperedTimestamp() {
        val secret = "test-secret"
        val body = """{"a":1}""".toByteArray()
        val signature = HmacSigner.sign(secret, "1720000000", body)

        assertFalse(HmacSigner.verify(secret, "1720000001", body, signature))
    }

    @Test
    fun verifyRejectsWrongSecret() {
        val body = """{"a":1}""".toByteArray()
        val signature = HmacSigner.sign("secret-a", "1720000000", body)

        assertFalse(HmacSigner.verify("secret-b", "1720000000", body, signature))
    }

    @Test
    fun signatureIsHexEncoded() {
        val signature = HmacSigner.sign("secret", "1720000000", ByteArray(0))

        assertTrue(signature.matches(Regex("^[0-9a-f]{64}$")))
    }
}
