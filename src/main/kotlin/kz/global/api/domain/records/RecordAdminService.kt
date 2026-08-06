package kz.global.api.domain.records

import kz.global.api.api.PatchRecordRequest
import kz.global.api.api.RecordEntry
import kz.global.api.api.RecordsPageResponse
import kz.global.api.db.tables.MapRecordsTable
import kz.global.api.db.tables.PlayersTable
import kz.global.api.domain.broadcast.BroadcastService
import kz.global.api.domain.replays.ReplayService
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEvent
import kz.global.api.events.KzEventBus
import kz.global.api.metrics.KzMetrics
import kz.global.api.security.WsPayloadValidator
import kz.global.api.storage.R2Client
import kz.global.api.util.RecordTimeParser
import kz.global.api.ws.DelRecordNotifyPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

data class AdminRecordFilters(
    val page: Int = 0,
    val size: Int = 20,
    val search: String? = null,
    val hasReplay: Boolean? = null,
    val flagged: Boolean? = null,
    val map: String? = null,
)

sealed class AdminRecordMutationResult {
    data class Deleted(val repair: RepairResult, val replayR2Key: String?) : AdminRecordMutationResult()
    data class Patched(val repair: RepairResult?) : AdminRecordMutationResult()
    data object NotFound : AdminRecordMutationResult()
}

class RecordAdminService(
    private val repairService: LeaderboardRepairService,
    private val replayService: ReplayService,
    private val r2Client: R2Client,
    private val auditLogger: AuditLogger,
    private val eventBus: KzEventBus,
    private val broadcastService: BroadcastService,
    private val metrics: KzMetrics,
    private val applicationScope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(RecordAdminService::class.java)

    suspend fun list(filters: AdminRecordFilters): RecordsPageResponse = suspendTransaction {
        val page = filters.page.coerceAtLeast(0)
        val size = filters.size.coerceIn(1, 100)
        val offset = page.toLong() * size

        val whereOp = buildWhere(filters)
        val base = MapRecordsTable.innerJoin(
            PlayersTable,
            { MapRecordsTable.playerSteamid },
            { PlayersTable.steamid },
        )

        val totalCount = if (whereOp != null) {
            base.selectAll().where { whereOp }.count()
        } else {
            base.selectAll().count()
        }

        val query = if (whereOp != null) {
            base.selectAll().where { whereOp }
        } else {
            base.selectAll()
        }

        val items = query
            .orderBy(MapRecordsTable.createdAt, SortOrder.DESC)
            .limit(size)
            .offset(offset)
            .map { row ->
                val replayKey = row[MapRecordsTable.replayR2Key]
                RecordEntry(
                    id = row[MapRecordsTable.id].toString(),
                    playerSteamid = row[MapRecordsTable.playerSteamid],
                    playerNickname = row[PlayersTable.lastNickname],
                    mapName = row[MapRecordsTable.mapName],
                    timeMs = row[MapRecordsTable.timeMs],
                    checkpoints = row[MapRecordsTable.checkpoints],
                    gochecks = row[MapRecordsTable.gochecks],
                    flagged = row[MapRecordsTable.flagged],
                    reviewed = row[MapRecordsTable.reviewed],
                    hasReplay = replayKey != null,
                    createdAt = row[MapRecordsTable.createdAt].toString(),
                )
            }

        val totalPages = if (totalCount == 0L) {
            0
        } else {
            ((totalCount + size - 1) / size).toInt()
        }

        RecordsPageResponse(
            items = items,
            page = page,
            totalPages = totalPages,
            totalCount = totalCount,
        )
    }

    suspend fun deleteRecord(recordId: Uuid): AdminRecordMutationResult {
        val outcome = suspendTransaction {
            val snapshot = loadSnapshot(recordId) ?: return@suspendTransaction null
            val (prevPro, prevNub) = repairService.captureWrIds(snapshot.mapName)

            MapRecordsTable.deleteWhere { MapRecordsTable.id eq recordId }

            val repair = repairService.repairForRecord(
                snapshot,
                invalidated = true,
                previousWrProId = prevPro,
                previousWrNubId = prevNub,
            )
            Triple(snapshot, repair, snapshot.replayR2Key)
        } ?: return AdminRecordMutationResult.NotFound

        val (_, repair, r2Key) = outcome

        runPostCommitEffects(
            repair = repair,
            sendInvalidationNotify = true,
            auditEvent = "RECORD_DELETED",
            auditExtra = buildJsonObject { put("record_id", recordId.toString()) },
        )

        r2Key?.let { key ->
            runCatching { r2Client.delete(key) }
                .onFailure { log.warn("Failed to delete R2 replay {}: {}", key, it.message) }
        }

        return AdminRecordMutationResult.Deleted(repair, r2Key)
    }

    suspend fun patchRecord(recordId: Uuid, req: PatchRecordRequest): AdminRecordMutationResult {
        if (req.flagged == null && req.reviewed == null && req.timeMs == null) {
            val exists = suspendTransaction {
                MapRecordsTable.selectAll().where { MapRecordsTable.id eq recordId }.count() > 0
            }
            return if (exists) AdminRecordMutationResult.Patched(null) else AdminRecordMutationResult.NotFound
        }

        val outcome = suspendTransaction {
            val snapshot = loadSnapshot(recordId) ?: return@suspendTransaction null
            val (prevPro, prevNub) = repairService.captureWrIds(snapshot.mapName)

            MapRecordsTable.update({ MapRecordsTable.id eq recordId }) {
                if (req.flagged != null) it[flagged] = req.flagged
                if (req.reviewed != null) it[reviewed] = req.reviewed
                if (req.timeMs != null) it[timeMs] = req.timeMs
            }

            val needsRepair = req.flagged != null || req.timeMs != null
            val repair = if (needsRepair) {
                val invalidated = req.flagged == true
                repairService.repairForRecord(
                    snapshot,
                    invalidated = invalidated,
                    previousWrProId = prevPro,
                    previousWrNubId = prevNub,
                )
            } else {
                null
            }

            Triple(snapshot, repair, req.flagged)
        } ?: return AdminRecordMutationResult.NotFound

        val (snapshot, repair, newFlagged) = outcome

        if (newFlagged == true && !snapshot.flagged) {
            metrics.flaggedRecords.increment()
        }

        if (repair != null) {
            val sendNotify = !snapshot.flagged && newFlagged == true
            runPostCommitEffects(
                repair = repair,
                sendInvalidationNotify = sendNotify,
                auditEvent = "RECORD_PATCHED",
                auditExtra = buildJsonObject {
                    put("record_id", recordId.toString())
                    req.flagged?.let { put("flagged", it) }
                    req.reviewed?.let { put("reviewed", it) }
                    req.timeMs?.let { put("time_ms", it) }
                },
            )
        } else {
            auditLogger.log(
                "RECORD_PATCHED",
                null,
                buildJsonObject {
                    put("record_id", recordId.toString())
                    req.reviewed?.let { put("reviewed", it) }
                },
            )
        }

        return AdminRecordMutationResult.Patched(repair)
    }

    private suspend fun runPostCommitEffects(
        repair: RepairResult,
        sendInvalidationNotify: Boolean,
        auditEvent: String,
        auditExtra: kotlinx.serialization.json.JsonObject,
    ) {
        auditLogger.log(auditEvent, null, auditExtra)

        if (repair.wrChanged) {
            emitWrEvents(repair)
        }

        if (sendInvalidationNotify) {
            broadcastInvalidation(repair)
        }

        applicationScope.launch {
            runCatching { replayService.pruneReplaysOutsideTop10(repair.mapName, "pro") }
                .onFailure { log.warn("Replay prune failed for {} pro: {}", repair.mapName, it.message) }
            runCatching { replayService.pruneReplaysOutsideTop10(repair.mapName, "nub") }
                .onFailure { log.warn("Replay prune failed for {} nub: {}", repair.mapName, it.message) }
        }
    }

    private suspend fun emitWrEvents(repair: RepairResult) {
        if (repair.wrProChanged && repair.newWrProId != null) {
            loadRecordForEvent(repair.newWrProId)?.let { (steamid, timeMs) ->
                runCatching {
                    eventBus.emit(
                        KzEvent.NewWorldRecord(repair.newWrProId, steamid, repair.mapName, timeMs, "pro"),
                    )
                }.onFailure { log.warn("Failed to emit pro WR event: {}", it.message) }
            }
        }
        if (repair.wrNubChanged && repair.newWrNubId != null) {
            loadRecordForEvent(repair.newWrNubId)?.let { (steamid, timeMs) ->
                runCatching {
                    eventBus.emit(
                        KzEvent.NewWorldRecord(repair.newWrNubId, steamid, repair.mapName, timeMs, "nub"),
                    )
                }.onFailure { log.warn("Failed to emit nub WR event: {}", it.message) }
            }
        }
    }

    private suspend fun broadcastInvalidation(repair: RepairResult) {
        if (WsPayloadValidator.validateMapName(repair.mapName) != null) return
        if (WsPayloadValidator.validateLocalUid(repair.localUid) != null) return

        val payload = DelRecordNotifyPayload(
            recordId = repair.recordId.toString(),
            mapName = repair.mapName,
            localUid = repair.localUid,
        )
        runCatching { broadcastService.broadcastRecordInvalidated(repair.mapName, payload) }
            .onFailure { log.warn("Failed to broadcast record invalidation: {}", it.message) }
    }

    private suspend fun loadRecordForEvent(recordId: Uuid): Pair<String, Long>? = suspendTransaction {
        MapRecordsTable
            .selectAll()
            .where { MapRecordsTable.id eq recordId }
            .singleOrNull()
            ?.let { it[MapRecordsTable.playerSteamid] to it[MapRecordsTable.timeMs] }
    }

    private fun loadSnapshot(recordId: Uuid): RecordSnapshot? =
        MapRecordsTable
            .selectAll()
            .where { MapRecordsTable.id eq recordId }
            .singleOrNull()
            ?.let { row ->
                RecordSnapshot(
                    id = row[MapRecordsTable.id],
                    playerSteamid = row[MapRecordsTable.playerSteamid],
                    mapName = row[MapRecordsTable.mapName],
                    timeMs = row[MapRecordsTable.timeMs],
                    gochecks = row[MapRecordsTable.gochecks],
                    localUid = row[MapRecordsTable.localUid],
                    flagged = row[MapRecordsTable.flagged],
                    replayR2Key = row[MapRecordsTable.replayR2Key],
                )
            }

    private fun buildWhere(filters: AdminRecordFilters): Op<Boolean>? {
        val parts = mutableListOf<Op<Boolean>>()

        if (filters.flagged == true) {
            parts += MapRecordsTable.flagged eq true
        }

        filters.map?.trim()?.takeIf { it.isNotEmpty() }?.let { mapName ->
            parts += MapRecordsTable.mapName eq mapName
        }

        when (filters.hasReplay) {
            true -> parts += MapRecordsTable.replayR2Key.isNotNull()
            false -> parts += MapRecordsTable.replayR2Key.isNull()
            null -> {}
        }

        filters.search?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val escaped = escapeLike(raw)
            val pattern = "%$escaped%"
            val textMatch = (MapRecordsTable.mapName like pattern) or
                (MapRecordsTable.playerSteamid like pattern) or
                (PlayersTable.lastNickname like pattern)

            val parsedTimeMs = RecordTimeParser.parseToMs(raw)
            parts += if (parsedTimeMs != null) {
                textMatch or (MapRecordsTable.timeMs eq parsedTimeMs)
            } else {
                textMatch
            }
        }

        return parts.reduceOrNull { acc, op -> acc and op }
    }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
