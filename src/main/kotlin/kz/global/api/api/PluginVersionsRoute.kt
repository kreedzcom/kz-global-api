package kz.global.api.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.global.api.db.tables.PluginVersionsTable
import kz.global.api.util.fromHex
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

@Serializable
data class CreatePluginVersionRequest(
    val semver: String,
    @SerialName("checksum_linux") val checksumLinux: String,
    @SerialName("checksum_windows") val checksumWindows: String,
    @SerialName("is_cutoff") val isCutoff: Boolean = false,
)

@Serializable
data class CreatePluginVersionResponse(val id: Int, val semver: String)

@Serializable
data class PluginVersionEntry(
    val id: Int,
    val semver: String,
    @SerialName("is_cutoff") val isCutoff: Boolean,
    @SerialName("created_at") val createdAt: String,
)

fun Route.pluginVersionsRoute() {
    route("/admin/plugin-versions") {
        authenticate("admin") {
            get {
                val versions = suspendTransaction {
                    PluginVersionsTable.selectAll().orderBy(PluginVersionsTable.createdAt).map { row ->
                        PluginVersionEntry(
                            id = row[PluginVersionsTable.id],
                            semver = row[PluginVersionsTable.semver],
                            isCutoff = row[PluginVersionsTable.isCutoff],
                            createdAt = row[PluginVersionsTable.createdAt].toString(),
                        )
                    }
                }
                call.respond(versions)
            }

            post {
                val req = call.receive<CreatePluginVersionRequest>()

                val linuxBytes = runCatching { req.checksumLinux.fromHex() }.getOrNull()
                    ?.takeIf { it.size == 16 }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "checksum_linux must be 32 hex chars")

                val windowsBytes = runCatching { req.checksumWindows.fromHex() }.getOrNull()
                    ?.takeIf { it.size == 16 }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "checksum_windows must be 32 hex chars")

                val id = suspendTransaction {
                    PluginVersionsTable.insert {
                        it[semver] = req.semver
                        it[checksumLinux] = linuxBytes
                        it[checksumWindows] = windowsBytes
                        it[isCutoff] = req.isCutoff
                    }[PluginVersionsTable.id]
                }
                call.respond(HttpStatusCode.Created, CreatePluginVersionResponse(id, req.semver))
            }

            patch("/{id}/cutoff") {
                val versionId = call.parameters["id"]?.toIntOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid id")

                suspendTransaction {
                    PluginVersionsTable.update({ PluginVersionsTable.id eq versionId }) {
                        it[isCutoff] = true
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
