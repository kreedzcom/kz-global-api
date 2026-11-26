package kz.global.api.ws.handlers

import kz.global.api.db.tables.MapRecordsTable
import kz.global.api.domain.records.RecordService
import kz.global.api.domain.replays.KrpReplayValidator
import kz.global.api.domain.replays.KrpValidation
import kz.global.api.domain.replays.ReplayAssemblyResult
import kz.global.api.domain.replays.ReplayIdentity
import kz.global.api.domain.replays.ReplayService
import kz.global.api.metrics.KzMetrics
import kz.global.api.security.WsRateLimiters
import kz.global.api.ws.FileAckPayload
import kz.global.api.ws.GameServerSession
import kz.global.api.ws.MsgType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

class ReplayChunkHandler(
    private val replayService: ReplayService,
    private val recordService: RecordService,
    private val metrics: KzMetrics,
    private val rateLimiters: WsRateLimiters,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val log = LoggerFactory.getLogger(ReplayChunkHandler::class.java)
    private val validationSlots = Semaphore(2)

    suspend fun handle(session: GameServerSession, bytes: ByteArray) {
        if (!rateLimiters.replayBytesByServer.tryAcquire(session.serverId.toString(), bytes.size)) {
            log.warn("Server {}: replay byte rate limit exceeded", session.serverId)
            return
        }

        val chunk = replayService.parseChunk(bytes)
        if (chunk == null) {
            log.warn("Server {}: malformed replay chunk ({} bytes)", session.serverId, bytes.size)
            return
        }

        log.debug("Server {}: chunk {}/{} for uid={}", session.serverId, chunk.index + 1, chunk.total, chunk.localUid)

        when (val assembly = replayService.receive(chunk, session.serverId)) {
            is ReplayAssemblyResult.Pending -> return
            is ReplayAssemblyResult.Rejected -> {
                when (assembly) {
                    is ReplayAssemblyResult.Rejected.CrcMismatch,
                    is ReplayAssemblyResult.Rejected.BadZstdMagic,
                    -> metrics.replayUploadFailures.increment()
                    is ReplayAssemblyResult.Rejected.InvalidChunk,
                    is ReplayAssemblyResult.Rejected.TooLarge,
                    is ReplayAssemblyResult.Rejected.TooManyConcurrent,
                    -> Unit
                }
                session.sendJson(MsgType.FILE_ACK, 0, FileAckPayload(localUid = chunk.localUid, status = false))
                return
            }
            is ReplayAssemblyResult.Complete -> {
                session.socket.launch {
                    val status = try {
                        finishReplay(session.serverId, chunk.localUid, assembly.bytes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        metrics.replayUploadFailures.increment()
                        log.error("Server {}: failed to commit replay for {}", session.serverId, chunk.localUid, e)
                        false
                    }
                    session.sendJson(MsgType.FILE_ACK, 0, FileAckPayload(chunk.localUid, status))
                }.invokeOnCompletion { assembly.close() }
            }
        }
    }

    private suspend fun findRecord(serverId: Int, localUid: String) = withContext(ioDispatcher) {
        suspendTransaction {
            MapRecordsTable.selectAll().where {
                (MapRecordsTable.localUid eq localUid) and (MapRecordsTable.serverId eq serverId)
            }.singleOrNull()
        }
    }

    private suspend fun finishReplay(serverId: Int, localUid: String, bytes: ByteArray): Boolean {
        val record = findRecord(serverId, localUid) ?: return false
        if (record[MapRecordsTable.replayR2Key] != null) return true

        val id = record[MapRecordsTable.id]
        val map = record[MapRecordsTable.mapName]
        val gochecks = record[MapRecordsTable.gochecks]
        val category = replayService.categoryForGochecks(gochecks)
        val identity = ReplayIdentity(record[MapRecordsTable.playerSteamid], map, record[MapRecordsTable.checkpoints], gochecks)
        val validation = validationSlots.withPermit {
            withContext(ioDispatcher) { KrpReplayValidator.validate(bytes, identity) }
        }
        val pending = record[MapRecordsTable.leaderboardPending]
        val flagged = record[MapRecordsTable.flagged]
        if (!pending && !flagged) {
            if (validation is KrpValidation.Rejected) return false
            if (!replayService.isRecordInTop10(id, map, category)) return true
        }

        val key = replayService.stageReplay(id, bytes)
        val committed = when {
            validation is KrpValidation.Rejected -> recordService.flagInvalidReplay(id, validation.reason, key)
            pending && !flagged -> recordService.finalizePendingLeaderboard(id, key)
            else -> replayService.attachReplay(id, key, flagged)
        }
        if (!committed) {
            replayService.discardUnattachedReplay(key)
            return findRecord(serverId, localUid)?.get(MapRecordsTable.replayR2Key) != null
        }
        if (validation is KrpValidation.Accepted && !flagged) {
            try {
                replayService.pruneReplaysOutsideTop10(map, category)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to prune replays for {}", map, e)
            }
        }
        return true
    }

}
