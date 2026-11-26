package kz.global.api.ws.handlers

import kz.global.api.domain.records.RecordResult
import kz.global.api.domain.records.RecordService
import kz.global.api.security.WsPayloadValidator
import kz.global.api.security.WsRateLimiters
import kz.global.api.ws.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AddRecordHandler(
    private val recordService: RecordService,
    private val rateLimiters: WsRateLimiters,
) {

    private val log = LoggerFactory.getLogger(AddRecordHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        if (!rateLimiters.addRecordByServer.tryAcquire(session.serverId.toString())) {
            session.sendError(envelope.msgId, "Rate limit exceeded", retryable = true)
            return
        }

        val payload = try {
            json.decodeFromJsonElement(AddRecordPayload.serializer(), envelope.data)
        } catch (_: SerializationException) {
            session.sendError(envelope.msgId, "Invalid message format", retryable = false)
            return
        }

        WsPayloadValidator.validateAddRecord(payload)?.let {
            session.sendError(envelope.msgId, it, retryable = false)
            return
        }

        if (payload.checkpoints !in 0..65_535) {
            session.sendError(envelope.msgId, "Invalid checkpoints", retryable = false)
            return
        }
        if (payload.gochecks !in 0..65_535) {
            session.sendError(envelope.msgId, "Invalid gochecks", retryable = false)
            return
        }
        if (payload.gochecks > 0 && payload.checkpoints <= 0) {
            session.sendError(envelope.msgId, "checkpoints must be positive when gochecks is positive", retryable = false)
            return
        }

        val pluginVersionId = session.pluginVersionId
        if (pluginVersionId <= 0) {
            session.sendError(envelope.msgId, "HELLO must be sent first with a valid plugin_version / plugin_checksum", retryable = true)
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
                session.sendError(envelope.msgId, "Record rejected", retryable = false)
            }
        }
    }

}
