package kz.global.api.ws.handlers

import kz.global.api.ws.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class PlayerLeaveHandler {

    private val log = LoggerFactory.getLogger(PlayerLeaveHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        val payload = json.decodeFromJsonElement(PlayerLeavePayload.serializer(), envelope.data)
        session.removePlayer(payload.steamid)
        log.debug("Server {}: player {} left", session.serverId, payload.steamid)
    }

}
