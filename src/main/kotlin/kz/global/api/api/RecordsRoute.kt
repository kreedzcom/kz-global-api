package kz.global.api.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.global.api.db.tables.*
import kz.global.api.domain.broadcast.BroadcastService
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

@Serializable
data class RecordEntry(
    val id: String,
    @SerialName("player_steamid") val playerSteamid: String,
    @SerialName("map_name") val mapName: String,
    @SerialName("time_ms") val timeMs: Long,
    val teleports: Int,
    val flagged: Boolean,
    val reviewed: Boolean,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PatchRecordRequest(
    val flagged: Boolean? = null,
    val reviewed: Boolean? = null,
    @SerialName("time_ms") val timeMs: Long? = null,
)

fun Route.recordsRoute() {
    val broadcastService by inject<BroadcastService>()

    route("/admin/records") {
        authenticate("admin") {
            get {
                val flaggedOnly = call.request.queryParameters["flagged"]?.toBooleanStrictOrNull()
                val mapFilter = call.request.queryParameters["map"]

                val records = suspendTransaction() {
                    MapRecordsTable.selectAll().apply {
                        if (flaggedOnly == true) andWhere { MapRecordsTable.flagged eq true }
                        if (mapFilter != null) andWhere { MapRecordsTable.mapName eq mapFilter }
                    }.orderBy(MapRecordsTable.createdAt, SortOrder.DESC)
                     .limit(100)
                     .map { row ->
                         RecordEntry(
                             id = row[MapRecordsTable.id].toString(),
                             playerSteamid = row[MapRecordsTable.playerSteamid],
                             mapName = row[MapRecordsTable.mapName],
                             timeMs = row[MapRecordsTable.timeMs],
                             teleports = row[MapRecordsTable.teleports],
                             flagged = row[MapRecordsTable.flagged],
                             reviewed = row[MapRecordsTable.reviewed],
                             createdAt = row[MapRecordsTable.createdAt].toString(),
                         )
                     }
                }
                call.respond(records)
            }

            patch("/{id}") {
                val recordId = runCatching { Uuid.parse(call.parameters["id"]!!) }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid UUID")

                val req = call.receive<PatchRecordRequest>()

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

                val mapName = suspendTransaction() {
                    val row = MapRecordsTable.selectAll()
                        .where { MapRecordsTable.id eq recordId }
                        .singleOrNull()
                        ?: return@suspendTransaction null

                    val mapName = row[MapRecordsTable.mapName]
                    MapRecordsTable.deleteWhere { id eq recordId }
                    mapName
                }

                if (mapName != null) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
