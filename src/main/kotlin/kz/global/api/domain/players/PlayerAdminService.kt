package kz.global.api.domain.players

import kz.global.api.api.PlayerEntry
import kz.global.api.api.PlayersPageResponse
import kz.global.api.db.tables.PlayersTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

data class AdminPlayerFilters(
    val page: Int = 0,
    val size: Int = 20,
    val search: String? = null,
    val banned: Boolean? = null,
)

class PlayerAdminService {

    suspend fun list(filters: AdminPlayerFilters): PlayersPageResponse = suspendTransaction {
        val page = filters.page.coerceAtLeast(0)
        val size = filters.size.coerceIn(1, 100)
        val offset = page.toLong() * size

        val whereOp = buildWhere(filters)
        val totalCount = if (whereOp != null) {
            PlayersTable.selectAll().where { whereOp }.count()
        } else {
            PlayersTable.selectAll().count()
        }

        val query = if (whereOp != null) {
            PlayersTable.selectAll().where { whereOp }
        } else {
            PlayersTable.selectAll()
        }

        val items = query
            .orderBy(PlayersTable.lastSeenAt, SortOrder.DESC)
            .limit(size)
            .offset(offset)
            .map { row ->
                PlayerEntry(
                    steamid = row[PlayersTable.steamid],
                    lastNickname = row[PlayersTable.lastNickname],
                    ipAddress = row[PlayersTable.ipAddress],
                    firstSeenAt = row[PlayersTable.firstSeenAt].toString(),
                    lastSeenAt = row[PlayersTable.lastSeenAt].toString(),
                    isBanned = row[PlayersTable.isBanned],
                )
            }

        val totalPages = if (totalCount == 0L) {
            0
        } else {
            ((totalCount + size - 1) / size).toInt()
        }

        PlayersPageResponse(
            items = items,
            page = page,
            totalPages = totalPages,
            totalCount = totalCount,
        )
    }

    private fun buildWhere(filters: AdminPlayerFilters): Op<Boolean>? {
        val parts = mutableListOf<Op<Boolean>>()

        when (filters.banned) {
            true -> parts += PlayersTable.isBanned eq true
            false -> parts += PlayersTable.isBanned eq false
            null -> {}
        }

        filters.search?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val escaped = escapeLike(raw)
            val pattern = "%$escaped%"
            parts += (PlayersTable.steamid like pattern) or (PlayersTable.lastNickname like pattern)
        }

        return parts.reduceOrNull { acc, op -> acc and op }
    }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

}
