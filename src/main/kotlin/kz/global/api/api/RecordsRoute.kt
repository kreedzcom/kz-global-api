package kz.global.api.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.global.api.db.tables.*
import kz.global.api.domain.records.AdminRecordFilters
import kz.global.api.domain.records.RecordAdminService
import kz.global.api.security.WsPayloadValidator
import kz.global.api.storage.R2Client
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.getKoin
import kotlin.uuid.Uuid

@Serializable
data class RecordEntry(
    val id: String,
    @SerialName("player_steamid") val playerSteamid: String,
    @SerialName("player_nickname") val playerNickname: String,
    @SerialName("map_name") val mapName: String,
    @SerialName("time_ms") val timeMs: Long,
    val checkpoints: Int,
    val gochecks: Int,
    val flagged: Boolean,
    val reviewed: Boolean,
    @SerialName("has_replay") val hasReplay: Boolean,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class RecordsPageResponse(
    val items: List<RecordEntry>,
    val page: Int,
    @SerialName("total_pages") val totalPages: Int,
    @SerialName("total_count") val totalCount: Long,
)

@Serializable
data class PatchRecordRequest(
    val flagged: Boolean? = null,
    val reviewed: Boolean? = null,
    @SerialName("time_ms") val timeMs: Long? = null,
)

fun Route.recordsRoute() {
    route("/admin/records") {
        authenticate("admin") {
            get {
                val params = call.request.queryParameters
                val page = params["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val size = params["size"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                val search = params["search"]?.trim()?.takeIf { it.isNotEmpty() }
                val hasReplay = params["has_replay"]?.toBooleanStrictOrNull()
                val flaggedOnly = params["flagged"]?.toBooleanStrictOrNull()
                val mapFilter = params["map"]?.trim()?.takeIf { it.isNotEmpty() }

                val service = call.getKoin().get<RecordAdminService>()
                val result = service.list(
                    AdminRecordFilters(
                        page = page,
                        size = size,
                        search = search,
                        hasReplay = hasReplay,
                        flagged = if (flaggedOnly == true) true else null,
                        map = mapFilter,
                    ),
                )
                call.respond(result)
            }

            patch("/{id}") {
                val recordId = runCatching { Uuid.parse(call.parameters["id"]!!) }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid UUID")

                val req = call.receive<PatchRecordRequest>()

                if (req.timeMs != null) {
                    val timeError = WsPayloadValidator.validateAdminRecordTime(req.timeMs)
                    if (timeError != null) {
                        return@patch call.respond(HttpStatusCode.BadRequest, timeError)
                    }
                }

                suspendTransaction() {
                    MapRecordsTable.update({ MapRecordsTable.id eq recordId }) {
                        if (req.flagged != null) it[flagged] = req.flagged
                        if (req.reviewed != null) it[reviewed] = req.reviewed
                        if (req.timeMs != null) it[timeMs] = req.timeMs
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/{id}") {
                val recordId = runCatching { Uuid.parse(call.parameters["id"]!!) }.getOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid UUID")

                val r2Client = call.getKoin().get<R2Client>()

                val outcome: Pair<Boolean, String?> = suspendTransaction() {
                    val row = MapRecordsTable.selectAll()
                        .where { MapRecordsTable.id eq recordId }
                        .singleOrNull()
                        ?: return@suspendTransaction Pair(false, null)

                    val replayKey = row[MapRecordsTable.replayR2Key]
                    MapRecordsTable.deleteWhere { MapRecordsTable.id eq recordId }
                    Pair(true, replayKey)
                }

                if (!outcome.first) {
                    call.respond(HttpStatusCode.NotFound)
                    return@delete
                }

                outcome.second?.let { key ->
                    runCatching { r2Client.delete(key) }
                }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
