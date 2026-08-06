package kz.global.api.domain.events

import kz.global.api.api.EventLogEntry
import kz.global.api.api.EventLogPageResponse
import kz.global.api.db.tables.EventLogTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

data class AdminEventLogFilters(
    val page: Int = 0,
    val size: Int = 20,
    val eventType: String? = null,
    val serverId: Int? = null,
)

class EventLogAdminService {

    suspend fun list(filters: AdminEventLogFilters): EventLogPageResponse = suspendTransaction {
        val page = filters.page.coerceAtLeast(0)
        val size = filters.size.coerceIn(1, 100)
        val offset = page.toLong() * size

        val whereOp = buildWhere(filters)
        val totalCount = if (whereOp != null) {
            EventLogTable.selectAll().where { whereOp }.count()
        } else {
            EventLogTable.selectAll().count()
        }

        val query = if (whereOp != null) {
            EventLogTable.selectAll().where { whereOp }
        } else {
            EventLogTable.selectAll()
        }

        val items = query
            .orderBy(EventLogTable.createdAt, SortOrder.DESC)
            .limit(size)
            .offset(offset)
            .map { row ->
                EventLogEntry(
                    id = row[EventLogTable.id],
                    serverId = row[EventLogTable.serverId],
                    eventType = row[EventLogTable.eventType],
                    payload = row[EventLogTable.payload],
                    createdAt = row[EventLogTable.createdAt].toString(),
                )
            }

        val totalPages = if (totalCount == 0L) {
            0
        } else {
            ((totalCount + size - 1) / size).toInt()
        }

        EventLogPageResponse(
            items = items,
            page = page,
            totalPages = totalPages,
            totalCount = totalCount,
        )
    }

    private fun buildWhere(filters: AdminEventLogFilters): Op<Boolean>? {
        val parts = mutableListOf<Op<Boolean>>()

        filters.eventType?.trim()?.takeIf { it.isNotEmpty() }?.let { eventType ->
            parts += EventLogTable.eventType eq eventType
        }

        filters.serverId?.let { serverId ->
            parts += EventLogTable.serverId eq serverId
        }

        return parts.reduceOrNull { acc, op -> acc and op }
    }

}
