package kz.global.api.domain.records

import kz.global.api.api.RecordEntry
import kz.global.api.api.RecordsPageResponse
import kz.global.api.db.tables.MapRecordsTable
import kz.global.api.db.tables.PlayersTable
import kz.global.api.util.RecordTimeParser
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

data class AdminRecordFilters(
    val page: Int = 0,
    val size: Int = 20,
    val search: String? = null,
    val hasReplay: Boolean? = null,
    val flagged: Boolean? = null,
    val map: String? = null,
)

class RecordAdminService {

    suspend fun list(filters: AdminRecordFilters): RecordsPageResponse = suspendTransaction {
        val page = filters.page.coerceAtLeast(0)
        val size = filters.size.coerceIn(1, 100)
        val offset = page.toLong() * size

        val whereOp = buildWhere(filters)
        val base = MapRecordsTable.innerJoin(
            PlayersTable,
            { MapRecordsTable.playerSteamid },
            { PlayersTable.steamid },
        )

        val totalCount = if (whereOp != null) {
            base.selectAll().where { whereOp }.count()
        } else {
            base.selectAll().count()
        }

        val query = if (whereOp != null) {
            base.selectAll().where { whereOp }
        } else {
            base.selectAll()
        }

        val items = query
            .orderBy(MapRecordsTable.createdAt, SortOrder.DESC)
            .limit(size)
            .offset(offset)
            .map { row ->
                val replayKey = row[MapRecordsTable.replayR2Key]
                RecordEntry(
                    id = row[MapRecordsTable.id].toString(),
                    playerSteamid = row[MapRecordsTable.playerSteamid],
                    playerNickname = row[PlayersTable.lastNickname],
                    mapName = row[MapRecordsTable.mapName],
                    timeMs = row[MapRecordsTable.timeMs],
                    checkpoints = row[MapRecordsTable.checkpoints],
                    gochecks = row[MapRecordsTable.gochecks],
                    flagged = row[MapRecordsTable.flagged],
                    reviewed = row[MapRecordsTable.reviewed],
                    hasReplay = replayKey != null,
                    createdAt = row[MapRecordsTable.createdAt].toString(),
                )
            }

        val totalPages = if (totalCount == 0L) {
            0
        } else {
            ((totalCount + size - 1) / size).toInt()
        }

        RecordsPageResponse(
            items = items,
            page = page,
            totalPages = totalPages,
            totalCount = totalCount,
        )
    }

    private fun buildWhere(filters: AdminRecordFilters): Op<Boolean>? {
        val parts = mutableListOf<Op<Boolean>>()

        if (filters.flagged == true) {
            parts += MapRecordsTable.flagged eq true
        }

        filters.map?.trim()?.takeIf { it.isNotEmpty() }?.let { mapName ->
            parts += MapRecordsTable.mapName eq mapName
        }

        when (filters.hasReplay) {
            true -> parts += MapRecordsTable.replayR2Key.isNotNull()
            false -> parts += MapRecordsTable.replayR2Key.isNull()
            null -> {}
        }

        filters.search?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val escaped = escapeLike(raw)
            val pattern = "%$escaped%"
            val textMatch = (MapRecordsTable.mapName like pattern) or
                (MapRecordsTable.playerSteamid like pattern) or
                (PlayersTable.lastNickname like pattern)

            val parsedTimeMs = RecordTimeParser.parseToMs(raw)
            parts += if (parsedTimeMs != null) {
                textMatch or (MapRecordsTable.timeMs eq parsedTimeMs)
            } else {
                textMatch
            }
        }

        return parts.reduceOrNull { acc, op -> acc and op }
    }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
