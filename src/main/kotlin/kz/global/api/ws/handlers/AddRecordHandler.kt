package kz.global.api.ws.handlers

import kz.global.api.domain.records.RecordResult
import kz.global.api.domain.records.RecordService
import kz.global.api.ws.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AddRecordHandler(private val recordService: RecordService) {

    private val log = LoggerFactory.getLogger(AddRecordHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        val payload = json.decodeFromJsonElement(AddRecordPayload.serializer(), envelope.data)

        val pluginVersionId = session.pluginVersionId
        if (pluginVersionId <= 0) {
            session.sendError(envelope.msgId, "HELLO must be sent first with a valid plugin_version / plugin_checksum")
            return
        }

        when (val result = recordService.submit(session.serverId, pluginVersionId, payload)) {
            is RecordResult.Accepted -> {
                session.sendJson(MsgType.RECORD_ACK, envelope.msgId, RecordAckPayload(
                    id = result.recordId.toString(),
                    localUid = payload.localUid,
                    isPb = result.isPb,
                ))
            }
            is RecordResult.Duplicate -> {
                session.sendJson(MsgType.RECORD_ACK, envelope.msgId, RecordAckPayload(
                    id = result.recordId.toString(),
                    localUid = payload.localUid,
                    isPb = false,
                ))
            }
            is RecordResult.Rejected -> {
                log.info("Record rejected for server {}: {}", session.serverId, result.reason)
                session.sendError(envelope.msgId, "Record rejected: ${result.reason}")
            }
        }
    }

}
