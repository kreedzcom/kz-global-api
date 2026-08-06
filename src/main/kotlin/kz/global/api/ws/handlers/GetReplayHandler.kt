package kz.global.api.ws.handlers

import kz.global.api.domain.replays.ReplayService
import kz.global.api.security.WsPayloadValidator
import kz.global.api.security.WsRateLimiters
import kz.global.api.ws.*
import kotlinx.serialization.json.Json

class GetReplayHandler(
    private val replayService: ReplayService,
    private val rateLimiters: WsRateLimiters,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        if (!rateLimiters.readQueryByServer.tryAcquire(session.serverId.toString())) {
            session.sendError(envelope.msgId, "Rate limit exceeded")
            return
        }

        val payload = json.decodeFromJsonElement(GetReplayPayload.serializer(), envelope.data)

        WsPayloadValidator.validateMapName(payload.mapName)?.let {
            session.sendError(envelope.msgId, it)
            return
        }

        val currentMap = session.currentMap
        if (currentMap.isBlank() || currentMap != payload.mapName) {
            session.sendError(envelope.msgId, "Replay request must match current map")
            return
        }

        val wrReplay = replayService.getWrReplayPresignedUrl(payload.mapName)
        if (wrReplay == null) {
            session.sendError(envelope.msgId, "Replay not available")
            return
        }

        WsPayloadValidator.validateLocalUid(wrReplay.localUid)?.let {
            session.sendError(envelope.msgId, "Replay not available")
            return
        }

        session.sendJson(
            MsgType.GET_REPLAY_ACK,
            envelope.msgId,
            GetReplayAckPayload(
                url = wrReplay.url,
                localUid = wrReplay.localUid,
                mapName = wrReplay.mapName,
            ),
        )
    }
}
