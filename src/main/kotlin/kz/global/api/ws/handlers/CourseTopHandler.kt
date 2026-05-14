package kz.global.api.ws.handlers

import kz.global.api.db.tables.*
import kz.global.api.ws.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.core.JoinType

class CourseTopHandler {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        val payload = json.decodeFromJsonElement(WantCourseTopPayload.serializer(), envelope.data)
        val limit = payload.limit.coerceIn(1, 100)

        val entries = suspendTransaction() {
            if (payload.category == "pro") {
                BestProRecordsTable
                    .join(MapRecordsTable, JoinType.INNER, BestProRecordsTable.recordId, MapRecordsTable.id)
                    .join(PlayersTable, JoinType.INNER, MapRecordsTable.playerSteamid, PlayersTable.steamid)
                    .selectAll()
                    .where { BestProRecordsTable.mapName eq payload.mapName }
                    .orderBy(MapRecordsTable.timeMs)
                    .toList()
                    .drop(payload.offset)
                    .take(limit)
                    .mapIndexed { idx, row ->
                        CourseTopEntry(
                            rank = payload.offset + idx + 1,
                            steamid = row[PlayersTable.steamid],
                            nickname = row[PlayersTable.lastNickname],
                            timeMs = row[MapRecordsTable.timeMs],
                            teleports = row[MapRecordsTable.teleports],
                        )
                    }
            } else {
                BestNubRecordsTable
                    .join(MapRecordsTable, JoinType.INNER, BestNubRecordsTable.recordId, MapRecordsTable.id)
                    .join(PlayersTable, JoinType.INNER, MapRecordsTable.playerSteamid, PlayersTable.steamid)
                    .selectAll()
                    .where { BestNubRecordsTable.mapName eq payload.mapName }
                    .orderBy(MapRecordsTable.timeMs)
                    .toList()
                    .drop(payload.offset)
                    .take(limit)
                    .mapIndexed { idx, row ->
                        CourseTopEntry(
                            rank = payload.offset + idx + 1,
                            steamid = row[PlayersTable.steamid],
                            nickname = row[PlayersTable.lastNickname],
                            timeMs = row[MapRecordsTable.timeMs],
                            teleports = row[MapRecordsTable.teleports],
                        )
                    }
            }
        }

        session.sendJson(MsgType.COURSE_TOP, envelope.msgId, CourseTopPayload(
            mapName = payload.mapName,
            category = payload.category,
            entries = entries,
        ))
    }
}
