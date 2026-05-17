package kz.global.api.ws.handlers

import kz.global.api.db.tables.PlayersTable
import kz.global.api.domain.players.PlayerBanService
import kz.global.api.security.WsPayloadValidator
import kz.global.api.ws.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import kotlin.time.Clock

class PlayerJoinHandler(
    private val playerBanService: PlayerBanService,
) {

    private val log = LoggerFactory.getLogger(PlayerJoinHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        val payload = json.decodeFromJsonElement(PlayerJoinPayload.serializer(), envelope.data)

        WsPayloadValidator.validateSteamId(payload.steamid)?.let {
            session.sendError(envelope.msgId, it)
            return
        }
        WsPayloadValidator.validateNickname(payload.nickname)?.let {
            session.sendError(envelope.msgId, it)
            return
        }

        val isBanned = playerBanService.isBanned(payload.steamid)

        if (!isBanned) {
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
        } else {
            log.info("Server {}: banned player {} attempted join", session.serverId, payload.steamid)
        }

        session.sendJson(MsgType.PLAYER_JOIN, envelope.msgId, PlayerJoinAckPayload(
            steamid = payload.steamid,
            isBanned = isBanned,
        ))
    }

}
