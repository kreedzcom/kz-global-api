package kz.global.api.ws.handlers

import kz.global.api.db.tables.*
import kz.global.api.ws.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class PlayerRecordsHandler {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        val payload = json.decodeFromJsonElement(WantPlayerRecordsPayload.serializer(), envelope.data)

        val records = suspendTransaction {
            MapRecordsTable
                .selectAll()
                .where {
                    (MapRecordsTable.playerSteamid eq payload.steamid) and
                    (MapRecordsTable.mapName eq payload.mapName) and
                    (MapRecordsTable.flagged eq false)
                }
                .orderBy(MapRecordsTable.timeMs)
                .map { row ->
                    PlayerRecordEntry(
                        mapName = row[MapRecordsTable.mapName],
                        timeMs = row[MapRecordsTable.timeMs],
                        recordId = row[MapRecordsTable.id].toString(),
                        checkpoints = row[MapRecordsTable.checkpoints],
                        gochecks = row[MapRecordsTable.gochecks],
                    )
                }
        }

        session.sendJson(MsgType.PLAYER_RECORDS, envelope.msgId, PlayerRecordsPayload(
            steamid = payload.steamid,
            records = records,
        ))
    }

}
