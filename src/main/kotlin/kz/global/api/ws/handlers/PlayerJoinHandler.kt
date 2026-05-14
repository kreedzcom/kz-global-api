package kz.global.api.ws.handlers

import kz.global.api.db.tables.PlayersTable
import kz.global.api.ws.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import kotlin.time.Clock

class PlayerJoinHandler {

    private val log = LoggerFactory.getLogger(PlayerJoinHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        val payload = json.decodeFromJsonElement(PlayerJoinPayload.serializer(), envelope.data)

        val now = Clock.System.now()
        suspendTransaction {
            PlayersTable.upsert(PlayersTable.steamid) {
                it[steamid] = payload.steamid
                it[lastNickname] = payload.nickname
                it[ipAddress] = payload.ipAddress
                it[lastSeenAt] = now
            }
        }

        session.addPlayer(ConnectedPlayer(payload.steamid, payload.nickname))
        log.debug("Server {}: player {} ({}) joined", session.serverId, payload.steamid, payload.nickname)

        session.sendJson(MsgType.PLAYER_JOIN, envelope.msgId, PlayerJoinAckPayload(
            steamid = payload.steamid,
            isBanned = false,
        ))
    }

}
