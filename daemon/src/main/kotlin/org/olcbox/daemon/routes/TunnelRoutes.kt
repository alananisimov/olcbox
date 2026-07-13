package org.olcbox.daemon.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.olcbox.daemon.ipc.StartTunnelRequest
import org.olcbox.daemon.tunnel.TunnelSupervisor

internal fun Route.tunnelRoutes(supervisor: TunnelSupervisor) {
    post("/tunnel/start") {
        val request = call.receive<StartTunnelRequest>()
        call.respond(HttpStatusCode.OK, supervisor.start(request))
    }
    post("/tunnel/stop") {
        call.respond(HttpStatusCode.OK, supervisor.stop())
    }
    get("/tunnel/status") {
        call.respond(HttpStatusCode.OK, supervisor.currentStatus())
    }
    get("/tunnel/logs") {
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        call.respond(HttpStatusCode.OK, supervisor.logsSince(since))
    }
}
