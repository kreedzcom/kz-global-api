package kz.global.api.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.global.api.domain.replays.ReplayService
import org.koin.ktor.ext.inject

fun Route.mapsReplayRoute() {
    val replayService by inject<ReplayService>()

    route("/admin/maps") {
        authenticate("admin") {
            get("/{mapName}/replay") {
                val mapName = call.parameters["mapName"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing mapName"))

                val category = call.request.queryParameters["category"] ?: "pro"
                if (category != "pro" && category != "nub") {
                    return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category"))
                }

                val bytes = replayService.getReplayBytes(mapName, category)
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Replay not found"))
                    return@get
                }

                call.response.header(HttpHeaders.ContentDisposition, "inline")
                call.respondBytes(bytes, ContentType.Application.OctetStream)
            }
        }
    }
}
