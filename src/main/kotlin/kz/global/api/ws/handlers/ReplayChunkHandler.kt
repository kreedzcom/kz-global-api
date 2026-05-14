package kz.global.api.ws.handlers

import kz.global.api.db.tables.MapRecordsTable
import kz.global.api.domain.replays.ReplayAssemblyResult
import kz.global.api.domain.replays.ReplayService
import kz.global.api.metrics.KzMetrics
import kz.global.api.ws.FileAckPayload
import kz.global.api.ws.GameServerSession
import kz.global.api.ws.MsgType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

class ReplayChunkHandler(
    private val replayService: ReplayService,
    private val metrics: KzMetrics,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val log = LoggerFactory.getLogger(ReplayChunkHandler::class.java)

    suspend fun handle(session: GameServerSession, bytes: ByteArray) {
        val chunk = replayService.parseChunk(bytes)
        if (chunk == null) {
            log.warn("Server {}: malformed replay chunk ({} bytes)", session.serverId, bytes.size)
            return
        }

        log.debug("Server {}: chunk {}/{} for uid={}", session.serverId, chunk.index + 1, chunk.total, chunk.localUid)

        when (val assembly = replayService.receive(chunk)) {
            is ReplayAssemblyResult.Pending -> return
            is ReplayAssemblyResult.Rejected -> {
                when (assembly) {
                    is ReplayAssemblyResult.Rejected.CrcMismatch,
                    is ReplayAssemblyResult.Rejected.BadZstdMagic,
                    -> metrics.replayUploadFailures.increment()
                    is ReplayAssemblyResult.Rejected.InvalidChunk -> Unit
                }
                session.sendJson(MsgType.FILE_ACK, 0, FileAckPayload(localUid = chunk.localUid, status = false))
                return
            }
            is ReplayAssemblyResult.Complete -> {
                val assembled = assembly.bytes

                val recordId = withContext(ioDispatcher) {
                    suspendTransaction {
                        MapRecordsTable
                            .selectAll()
                            .where {
                                (MapRecordsTable.localUid eq chunk.localUid) and
                                    (MapRecordsTable.serverId eq session.serverId)
                            }
                            .singleOrNull()
                            ?.get(MapRecordsTable.id)
                    }
                }

                if (recordId == null) {
                    log.warn(
                        "Server {}: replay complete for uid={} but no matching record for this server",
                        session.serverId,
                        chunk.localUid,
                    )
                    session.sendJson(MsgType.FILE_ACK, 0, FileAckPayload(localUid = chunk.localUid, status = false))
                    return
                }

                session.socket.launch {
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
        }
    }

}
