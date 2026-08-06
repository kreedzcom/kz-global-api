package kz.global.api.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.global.api.db.tables.GameServersTable
import kz.global.api.events.AuditLogger
import kz.global.api.security.AdminActor
import kz.global.api.util.toHex
import kz.global.api.ws.ConnectedServersRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.inject
import java.security.SecureRandom

@Serializable
data class CreateServerRequest(
    val name: String,
    @SerialName("allowed_ips") val allowedIps: String? = null,
)

@Serializable
data class PatchServerRequest(
    @SerialName("allowed_ips") val allowedIps: String? = null,
)

@Serializable
data class CreateServerResponse(val id: Int, val name: String, val accessKey: String)

@Serializable
data class ServerListEntry(val id: Int, val name: String, val active: Boolean, val lastConnectedAt: String?)

@Serializable
data class ConnectedServerEntry(
    @SerialName("server_id") val serverId: Int,
    @SerialName("current_map") val currentMap: String?,
)

fun Route.serversRoute() {
    val registry by inject<ConnectedServersRegistry>()
    val auditLogger by inject<AuditLogger>()

    route("/admin/servers") {
        authenticate("admin") {
            get {
                val servers = suspendTransaction {
                    GameServersTable.selectAll().map { row ->
                        ServerListEntry(
                            id = row[GameServersTable.id],
                            name = row[GameServersTable.name],
                            active = row[GameServersTable.active],
                            lastConnectedAt = row[GameServersTable.lastConnectedAt]?.toString(),
                        )
                    }
                }
                call.respond(servers)
            }

            get("/connected") {
                val connected = registry.allSessions().map { session ->
                    ConnectedServerEntry(
                        serverId = session.serverId,
                        currentMap = session.currentMap.ifBlank { null },
                    )
                }
                call.respond(connected)
            }

            post {
                val req = call.receive<CreateServerRequest>()
                val actor = AdminActor.fromCall(call)
                val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }

                val serverId = suspendTransaction() {
                    GameServersTable.insert {
                        it[name] = req.name
                        it[accessKey] = keyBytes
                        it[allowedIps] = req.allowedIps
                    }[GameServersTable.id]
                }

                auditLogger.log(
                    "SERVER_CREATED",
                    serverId,
                    AdminActor.withActor(
                        buildJsonObject {
                            put("name", req.name)
                        },
                        actor,
                    ),
                )

                call.respond(HttpStatusCode.Created, CreateServerResponse(
                    id = serverId,
                    name = req.name,
                    accessKey = keyBytes.toHex(),
                ))
            }

            patch("/{id}") {
                val serverId = call.parameters["id"]?.toIntOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid id")

                val req = call.receive<PatchServerRequest>()
                if (req.allowedIps == null) {
                    return@patch call.respond(HttpStatusCode.BadRequest, "No fields to update")
                }

                val actor = AdminActor.fromCall(call)

                suspendTransaction {
                    GameServersTable.update({ GameServersTable.id eq serverId }) {
                        it[allowedIps] = req.allowedIps
                    }
                }

                auditLogger.log(
                    "SERVER_PATCHED",
                    serverId,
                    AdminActor.withActor(
                        buildJsonObject {
                            put("allowed_ips", req.allowedIps)
                        },
                        actor,
                    ),
                )

                call.respond(HttpStatusCode.NoContent)
            }

            delete("/{id}") {
                val serverId = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid id")

                val actor = AdminActor.fromCall(call)

                suspendTransaction {
                    GameServersTable.update({ GameServersTable.id eq serverId }) {
                        it[active] = false
                    }
                }
                registry.disconnect(serverId)

                auditLogger.log(
                    "SERVER_DEACTIVATED",
                    serverId,
                    AdminActor.withActor(buildJsonObject {}, actor),
                )

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
