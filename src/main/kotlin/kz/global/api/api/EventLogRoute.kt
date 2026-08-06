package kz.global.api.api

import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kz.global.api.domain.events.AdminEventLogFilters
import kz.global.api.domain.events.EventLogAdminService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class EventLogEntry(
    val id: Long,
    @SerialName("server_id") val serverId: Int? = null,
    @SerialName("event_type") val eventType: String,
    val payload: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class EventLogPageResponse(
    val items: List<EventLogEntry>,
    val page: Int,
    @SerialName("total_pages") val totalPages: Int,
    @SerialName("total_count") val totalCount: Long,
)

fun Route.eventLogRoute() {
    val eventLogAdminService by inject<EventLogAdminService>()

    route("/admin/event-log") {
        authenticate("admin") {
            get {
                val params = call.request.queryParameters
                val page = params["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val size = params["size"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                val eventType = params["event_type"]?.trim()?.takeIf { it.isNotEmpty() }
                val serverId = params["server_id"]?.toIntOrNull()

                val result = eventLogAdminService.list(
                    AdminEventLogFilters(
                        page = page,
                        size = size,
                        eventType = eventType,
                        serverId = serverId,
                    ),
                )
                call.respond(result)
            }
        }
    }
}
