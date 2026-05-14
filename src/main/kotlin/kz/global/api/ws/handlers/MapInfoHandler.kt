package kz.global.api.ws.handlers

import kz.global.api.domain.broadcast.BroadcastService
import kz.global.api.ws.*
import kotlinx.serialization.json.Json

class MapInfoHandler(private val broadcastService: BroadcastService) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        val payload = json.decodeFromJsonElement(WantMapInfoPayload.serializer(), envelope.data)
        val mapInfo = broadcastService.getMapInfo(payload.mapName)
        if (mapInfo != null) {
            session.sendJson(MsgType.MAP_INFO, envelope.msgId, mapInfo)
        } else {
            session.sendError(envelope.msgId, "Map not found: ${payload.mapName}")
        }
    }

}
