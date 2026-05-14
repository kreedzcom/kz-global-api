package kz.global.api.ws.handlers

import kz.global.api.db.tables.MapRecordsTable
import kz.global.api.domain.replays.ReplayService
import kz.global.api.metrics.KzMetrics
import kz.global.api.ws.FileAckPayload
import kz.global.api.ws.GameServerSession
import kz.global.api.ws.MsgType
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory

class ReplayChunkHandler(
    private val replayService: ReplayService,
    private val metrics: KzMetrics,
) {

    private val log = LoggerFactory.getLogger(ReplayChunkHandler::class.java)

    suspend fun handle(session: GameServerSession, bytes: ByteArray) {
        val chunk = replayService.parseChunk(bytes)
        if (chunk == null) {
            log.warn("Server {}: malformed replay chunk ({} bytes)", session.serverId, bytes.size)
            return
        }

        log.debug("Server {}: chunk {}/{} for uid={}", session.serverId, chunk.index + 1, chunk.total, chunk.localUid)

        val assembled = replayService.receive(chunk) ?: return

        val recordId = newSuspendedTransaction(Dispatchers.IO) {
            MapRecordsTable
                .selectAll()
                .where { MapRecordsTable.localUid eq chunk.localUid }
                .singleOrNull()
                ?.get(MapRecordsTable.id)
        }

        if (recordId == null) {
            log.warn("Server {}: replay complete for uid={} but no matching record", session.serverId, chunk.localUid)
            session.sendJson(MsgType.FILE_ACK, 0, FileAckPayload(localUid = chunk.localUid, status = false))
            return
        }

        runCatching { replayService.storeReplay(recordId, chunk.localUid, assembled) }
            .onSuccess {
                session.sendJson(MsgType.FILE_ACK, 0, FileAckPayload(localUid = chunk.localUid, status = true))
                log.info("Server {}: replay stored for record {}", session.serverId, recordId)
            }
            .onFailure { e ->
                metrics.replayUploadFailures.increment()
                log.error("Server {}: failed to store replay for {}: {}", session.serverId, recordId, e.message)
                session.sendJson(MsgType.FILE_ACK, 0, FileAckPayload(localUid = chunk.localUid, status = false))
            }
    }

}
