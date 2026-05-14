package kz.global.api.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.global.api.db.tables.MapMinimumTimesTable
import kz.global.api.db.tables.MapsTable
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.time.Clock

@Serializable
data class MapMinimumTimeEntry(
    @SerialName("map_name") val mapName: String,
    @SerialName("min_time_ms") val minTimeMs: Long,
    @SerialName("updated_by") val updatedBy: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class SetMapMinimumTimeRequest(
    @SerialName("min_time_ms") val minTimeMs: Long,
)

fun Route.mapTimesRoute() {
    route("/admin/map-times") {
        authenticate("admin") {
            get {
                val entries = suspendTransaction {
                    MapMinimumTimesTable.selectAll().map { row ->
                        MapMinimumTimeEntry(
                            mapName = row[MapMinimumTimesTable.mapName],
                            minTimeMs = row[MapMinimumTimesTable.minTimeMs],
                            updatedBy = row[MapMinimumTimesTable.updatedBy],
                            updatedAt = row[MapMinimumTimesTable.updatedAt].toString(),
                        )
                    }
                }
                call.respond(entries)
            }

            put("/{mapName}") {
                val mapName = call.parameters["mapName"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing mapName")

                val req = call.receive<SetMapMinimumTimeRequest>()

                suspendTransaction {
                    MapsTable.insertIgnore { it[name] = mapName }
                    MapMinimumTimesTable.upsert(MapMinimumTimesTable.mapName) {
                        it[MapMinimumTimesTable.mapName] = mapName
                        it[minTimeMs] = req.minTimeMs
                        it[updatedBy] = "admin"
                        it[updatedAt] = Clock.System.now()
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/{mapName}") {
                val mapName = call.parameters["mapName"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing mapName")

                suspendTransaction {
                    MapMinimumTimesTable.deleteWhere { MapMinimumTimesTable.mapName eq mapName }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
