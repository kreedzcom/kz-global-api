package kz.global.api.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.global.api.db.tables.GameServersTable
import kz.global.api.util.toHex
import kz.global.api.ws.ConnectedServersRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.inject
import java.security.SecureRandom

@Serializable
data class CreateServerRequest(val name: String)

@Serializable
data class CreateServerResponse(val id: Int, val name: String, val accessKey: String)

@Serializable
data class ServerListEntry(val id: Int, val name: String, val active: Boolean, val lastConnectedAt: String?)

fun Route.serversRoute() {
    val registry by inject<ConnectedServersRegistry>()

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
                val connected = registry.allSessions().map { s ->
                    mapOf("server_id" to s.serverId, "current_map" to s.currentMap)
                }
                call.respond(connected)
            }

            post {
                val req = call.receive<CreateServerRequest>()
                val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }

                val serverId = suspendTransaction() {
                    GameServersTable.insert {
                        it[name] = req.name
                        it[accessKey] = keyBytes
                    }[GameServersTable.id]
                }

                call.respond(HttpStatusCode.Created, CreateServerResponse(
                    id = serverId,
                    name = req.name,
                    accessKey = keyBytes.toHex(),
                ))
            }

            delete("/{id}") {
                val serverId = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid id")

                suspendTransaction {
                    GameServersTable.update({ GameServersTable.id eq serverId }) {
                        it[active] = false
                    }
                }
                registry.disconnect(serverId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
