package kz.global.api.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.global.api.domain.players.PlayerBanService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class BanPlayerRequest(
    @SerialName("is_banned") val isBanned: Boolean,
)

fun Route.playersRoute() {
    val banService by inject<PlayerBanService>()

    route("/admin/players") {
        authenticate("admin") {
            patch("/{steamid}/ban") {
                val steamid = call.parameters["steamid"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing steamid")

                val req = call.receive<BanPlayerRequest>()
                banService.setBanned(steamid, req.isBanned)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
