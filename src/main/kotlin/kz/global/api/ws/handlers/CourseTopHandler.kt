package kz.global.api.ws.handlers

import kz.global.api.db.tables.*
import kz.global.api.security.WsPayloadValidator
import kz.global.api.security.WsRateLimiters
import kz.global.api.ws.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.core.JoinType

class CourseTopHandler(
    private val rateLimiters: WsRateLimiters,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        if (!rateLimiters.readQueryByServer.tryAcquire(session.serverId.toString())) {
            session.sendError(envelope.msgId, "Rate limit exceeded")
            return
        }

        val payload = json.decodeFromJsonElement(WantCourseTopPayload.serializer(), envelope.data)

        WsPayloadValidator.validateMapName(payload.mapName)?.let {
            session.sendError(envelope.msgId, it)
            return
        }

        val limit = payload.limit.coerceIn(1, 100)
        val offset = payload.offset.coerceAtLeast(0)

        val entries = suspendTransaction {
            if (payload.category == "pro") {
                BestProRecordsTable
                    .join(MapRecordsTable, JoinType.INNER, BestProRecordsTable.recordId, MapRecordsTable.id)
                    .join(PlayersTable, JoinType.INNER, MapRecordsTable.playerSteamid, PlayersTable.steamid)
                    .selectAll()
                    .where { BestProRecordsTable.mapName eq payload.mapName }
                    .orderBy(MapRecordsTable.timeMs)
                    .limit(limit)
                    .offset(offset.toLong())
                    .toList()
                    .mapIndexed { idx, row ->
                        CourseTopEntry(
                            rank = offset + idx + 1,
                            steamid = row[PlayersTable.steamid],
                            nickname = row[PlayersTable.lastNickname],
                            timeMs = row[MapRecordsTable.timeMs],
                            checkpoints = row[MapRecordsTable.checkpoints],
                            gochecks = row[MapRecordsTable.gochecks],
                        )
                    }
            } else {
                BestNubRecordsTable
                    .join(MapRecordsTable, JoinType.INNER, BestNubRecordsTable.recordId, MapRecordsTable.id)
                    .join(PlayersTable, JoinType.INNER, MapRecordsTable.playerSteamid, PlayersTable.steamid)
                    .selectAll()
                    .where { BestNubRecordsTable.mapName eq payload.mapName }
                    .orderBy(MapRecordsTable.timeMs)
                    .limit(limit)
                    .offset(offset.toLong())
                    .toList()
                    .mapIndexed { idx, row ->
                        CourseTopEntry(
                            rank = offset + idx + 1,
                            steamid = row[PlayersTable.steamid],
                            nickname = row[PlayersTable.lastNickname],
                            timeMs = row[MapRecordsTable.timeMs],
                            checkpoints = row[MapRecordsTable.checkpoints],
                            gochecks = row[MapRecordsTable.gochecks],
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
