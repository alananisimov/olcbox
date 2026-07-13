package org.olcbox.daemon

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.olcbox.daemon.auth.HmacAuthPlugin
import org.olcbox.daemon.auth.PermissionsHelper
import org.olcbox.daemon.ipc.DaemonPaths
import org.olcbox.daemon.routes.healthRoutes
import org.olcbox.daemon.routes.tunnelRoutes
import org.olcbox.daemon.tunnel.TunnelSupervisor

object DaemonServer {
    fun run() {
        val socketFile = File(DaemonPaths.SOCKET_PATH)
        socketFile.parentFile?.mkdirs()
        socketFile.delete()

        val supervisor = TunnelSupervisor()

        val server = embeddedServer(CIO, configure = { unixConnector(DaemonPaths.SOCKET_PATH) }) {
            // Installed before auth so rejected/unreachable-looking requests
            // still show up here — exactly the case that was invisible
            // during yesterday's "not installed or not running" confusion.
            install(CallLogging)
            install(DoubleReceive)
            install(ContentNegotiation) { json() }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (cause.message ?: "unknown error"))
                    )
                }
            }
            install(HmacAuthPlugin)
            routing {
                healthRoutes()
                tunnelRoutes(supervisor)
            }
        }.start(wait = false)

        PermissionsHelper.waitAndOpenSocketPermissions(socketFile)

        val shutdownLatch = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                // Tear down any running tunnel first — SIGINT/interface
                // cleanup is exactly the same logic TunnelSupervisor.stop()
                // already runs on a normal client-requested stop, so a
                // daemon restart (systemctl restart) never leaves an
                // orphaned olcRTC/hev-socks5-tunnel pair or a stale olcbox0
                // interface behind.
                runBlocking { supervisor.stop() }
                server.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_000)
                shutdownLatch.countDown()
            }
        )
        shutdownLatch.await()
    }
}
