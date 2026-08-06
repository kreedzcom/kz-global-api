package kz.global.api.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.global.api.domain.players.AdminPlayerFilters
import kz.global.api.domain.players.PlayerAdminService
import kz.global.api.domain.players.PlayerBanResult
import kz.global.api.domain.players.PlayerBanService
import kz.global.api.security.AdminActor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class BanPlayerRequest(
    @SerialName("is_banned") val isBanned: Boolean,
)

@Serializable
data class PlayerEntry(
    val steamid: String,
    @SerialName("last_nickname") val lastNickname: String,
    @SerialName("ip_address") val ipAddress: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @SerialName("is_banned") val isBanned: Boolean,
)

@Serializable
data class PlayersPageResponse(
    val items: List<PlayerEntry>,
    val page: Int,
    @SerialName("total_pages") val totalPages: Int,
    @SerialName("total_count") val totalCount: Long,
)

fun Route.playersRoute() {
    val banService by inject<PlayerBanService>()
    val playerAdminService by inject<PlayerAdminService>()

    route("/admin/players") {
        authenticate("admin") {
            get {
                val params = call.request.queryParameters
                val page = params["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val size = params["size"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                val search = params["search"]?.trim()?.takeIf { it.isNotEmpty() }
                val banned = params["banned"]?.toBooleanStrictOrNull()

                val result = playerAdminService.list(
                    AdminPlayerFilters(
                        page = page,
                        size = size,
                        search = search,
                        banned = banned,
                    ),
                )
                call.respond(result)
            }

            patch("/{steamid}/ban") {
                val steamid = call.parameters["steamid"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing steamid")

                val req = call.receive<BanPlayerRequest>()
                val actor = AdminActor.fromCall(call)

                when (banService.setBanned(steamid, req.isBanned, actor)) {
                    PlayerBanResult.Success -> call.respond(HttpStatusCode.NoContent)
                    PlayerBanResult.NotFound -> call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}
