package kz.global.api.ws.handlers

import kz.global.api.domain.broadcast.BroadcastService
import kz.global.api.security.WsPayloadValidator
import kz.global.api.ws.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class MapChangeHandler(private val broadcastService: BroadcastService) {

    private val log = LoggerFactory.getLogger(MapChangeHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        val payload = json.decodeFromJsonElement(MapChangePayload.serializer(), envelope.data)

        WsPayloadValidator.validateMapName(payload.mapName)?.let {
            session.sendError(envelope.msgId, it)
            return
        }

        session.currentMap = payload.mapName
        log.info("Server {} changed map to: {}", session.serverId, payload.mapName)

        val mapInfo = broadcastService.getMapInfo(payload.mapName)
        if (mapInfo != null) {
            session.sendJson(MsgType.MAP_INFO, envelope.msgId, mapInfo)
        }
    }

}
