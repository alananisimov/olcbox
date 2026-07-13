package org.olcbox.daemon.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// Exempt from HmacAuthPlugin — lets the user run a bare
// `curl --unix-socket <path> http://localhost/health` smoke test without
// needing to hand-construct a signed request.
const val HEALTH_PATH = "/health"

fun Route.healthRoutes() {
    get(HEALTH_PATH) {
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}
