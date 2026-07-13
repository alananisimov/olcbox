package org.olcbox.daemon.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.Comparator
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.olcbox.daemon.ipc.HmacHeaders
import org.olcbox.daemon.ipc.HmacSigner
import org.olcbox.daemon.ipc.IpcSecret
import org.olcbox.daemon.routes.HEALTH_PATH
import org.olcbox.daemon.routes.healthRoutes

class HmacAuthPluginTest {

    private lateinit var root: Path
    private lateinit var keyFile: Path
    private lateinit var secret: String

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("hmac-auth-test")
        val dir = root.resolve(IpcSecret.USER_FOLDER_NAME)
        keyFile = IpcSecret.getOrCreateSecret(dir)
        secret = IpcSecret.readSecret(keyFile)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::delete)
    }

    private fun testAppModule(): Application.() -> Unit = {
        install(DoubleReceive)
        install(ContentNegotiation) { json() }
        install(HmacAuthPlugin)
        routing {
            healthRoutes()
            post("/echo") {
                call.respondText(call.receiveText())
            }
        }
    }

    @Test
    fun healthIsExemptFromAuth() = testApplication {
        application(testAppModule())

        val response = client.get(HEALTH_PATH)

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun rejectsRequestWithoutHeaders() = testApplication {
        application(testAppModule())

        val response = client.post("/echo") { setBody("hello") }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun rejectsRequestWithWrongSignature() = testApplication {
        application(testAppModule())
        val timestamp = (System.currentTimeMillis() / 1000).toString()

        val response = client.post("/echo") {
            header(HmacHeaders.KEY_PATH, keyFile.toString())
            header(HmacHeaders.TIMESTAMP, timestamp)
            header(HmacHeaders.SIGNATURE, "deadbeef")
            setBody("hello")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun rejectsRequestWithUnexpectedKeyPathStructure() = testApplication {
        application(testAppModule())
        val decoyDir = Files.createTempDirectory("not-dot-olcbox")
        val decoyKey = decoyDir.resolve(IpcSecret.KEY_FILE_NAME)
        Files.writeString(decoyKey, secret)
        Files.setPosixFilePermissions(decoyKey, PosixFilePermissions.fromString("rw-------"))
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val body = "hello"
        val signature = HmacSigner.sign(secret, timestamp, body.toByteArray())

        val response = client.post("/echo") {
            header(HmacHeaders.KEY_PATH, decoyKey.toString())
            header(HmacHeaders.TIMESTAMP, timestamp)
            header(HmacHeaders.SIGNATURE, signature)
            setBody(body)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        Files.deleteIfExists(decoyKey)
        Files.deleteIfExists(decoyDir)
    }

    @Test
    fun rejectsStaleTimestamp() = testApplication {
        application(testAppModule())
        val staleTimestamp = (System.currentTimeMillis() / 1000 - 3600).toString()
        val body = "hello"
        val signature = HmacSigner.sign(secret, staleTimestamp, body.toByteArray())

        val response = client.post("/echo") {
            header(HmacHeaders.KEY_PATH, keyFile.toString())
            header(HmacHeaders.TIMESTAMP, staleTimestamp)
            header(HmacHeaders.SIGNATURE, signature)
            setBody(body)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun acceptsValidSignedRequest() = testApplication {
        application(testAppModule())
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val body = "hello"
        val signature = HmacSigner.sign(secret, timestamp, body.toByteArray())

        val response = client.post("/echo") {
            header(HmacHeaders.KEY_PATH, keyFile.toString())
            header(HmacHeaders.TIMESTAMP, timestamp)
            header(HmacHeaders.SIGNATURE, signature)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(body, response.bodyAsText())
    }
}
