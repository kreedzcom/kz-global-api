package kz.global.api.domain.records

import kz.global.api.db.tables.*
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEvent
import kz.global.api.events.KzEventBus
import kz.global.api.metrics.KzMetrics
import kz.global.api.util.uuidV7
import kz.global.api.ws.AddRecordPayload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.sql.SQLException
import kotlin.time.Clock
import kotlin.uuid.Uuid

sealed class RecordResult {
    data class Accepted(val recordId: Uuid, val isPb: Boolean) : RecordResult()
    data class Duplicate(val recordId: Uuid) : RecordResult()
    data class Rejected(val reason: String) : RecordResult()
}

private data class LeaderboardResult(
    val recordId: Uuid,
    val isPb: Boolean,
    val isWrNub: Boolean,
    val isWrPro: Boolean,
)

class RecordService(
    private val eventBus: KzEventBus,
    private val auditLogger: AuditLogger,
    private val metrics: KzMetrics,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(RecordService::class.java)

    suspend fun submit(
        serverId: Int,
        pluginVersionId: Int,
        payload: AddRecordPayload,
    ): RecordResult {
        val txResult = persistRecord(serverId, pluginVersionId, payload)

        if (txResult is LeaderboardResult) {
            emitEvents(txResult, payload, serverId)
            return RecordResult.Accepted(txResult.recordId, txResult.isPb)
        }
        return txResult as RecordResult
    }

    private suspend fun persistRecord(
        serverId: Int,
        pluginVersionId: Int,
        payload: AddRecordPayload,
    ): Any = withContext(ioDispatcher) {
        try {
            suspendTransaction {
                val existing = MapRecordsTable
                    .selectAll()
                    .where { MapRecordsTable.localUid eq payload.localUid }
                    .singleOrNull()

                if (existing != null) {
                    return@suspendTransaction RecordResult.Duplicate(existing[MapRecordsTable.id])
                }

                val minTime = MapMinimumTimesTable
                    .selectAll()
                    .where { MapMinimumTimesTable.mapName eq payload.mapName }
                    .singleOrNull()
                    ?.get(MapMinimumTimesTable.minTimeMs)

                if (minTime != null && payload.timeMs < minTime) {
                    log.warn("Rejected: {}ms below minimum {}ms for map {}", payload.timeMs, minTime, payload.mapName)
                    return@suspendTransaction RecordResult.Rejected("Time below map minimum")
                }

                MapsTable.insertIgnore { it[name] = payload.mapName }

                val now = Clock.System.now()
                PlayersTable.insertIgnore {
                    it[steamid] = payload.steamid
                    it[lastNickname] = payload.steamid
                    it[lastSeenAt] = now
                }

                val recordId = uuidV7()
                MapRecordsTable.insert {
                    it[id] = recordId
                    it[MapRecordsTable.serverId] = serverId
                    it[playerSteamid] = payload.steamid
                    it[mapName] = payload.mapName
                    it[timeMs] = payload.timeMs
                    it[checkpoints] = payload.checkpoints
                    it[gochecks] = payload.gochecks
                    it[localUid] = payload.localUid
                    it[MapRecordsTable.pluginVersionId] = pluginVersionId
                }

                val isPbNub = updateBestNub(payload.steamid, payload.mapName, recordId, payload.timeMs)
                val isPbPro = if (payload.gochecks == 0) {
                    updateBestPro(payload.steamid, payload.mapName, recordId, payload.timeMs)
                } else {
                    false
                }

                val isWrNub = isPbNub && updateWorldRecord(payload.mapName, "nub", recordId, payload.timeMs)
                val isWrPro = isPbPro && updateWorldRecord(payload.mapName, "pro", recordId, payload.timeMs)

                log.info(
                    "Accepted: {} record={} map={} time={}ms checkpoints={} gochecks={}",
                    payload.steamid,
                    recordId,
                    payload.mapName,
                    payload.timeMs,
                    payload.checkpoints,
                    payload.gochecks,
                )
                metrics.recordsSubmitted.increment()
                if (isWrNub || isWrPro) metrics.worldRecords.increment()
                LeaderboardResult(recordId, isPbNub || isPbPro, isWrNub, isWrPro)
            }
        } catch (e: Exception) {
            if (!isUniqueConstraintViolation(e)) throw e
            val dupId = suspendTransaction {
                MapRecordsTable
                    .selectAll()
                    .where { MapRecordsTable.localUid eq payload.localUid }
                    .singleOrNull()
                    ?.get(MapRecordsTable.id)
            } ?: throw e
            RecordResult.Duplicate(dupId)
        }
    }

    private fun isUniqueConstraintViolation(t: Throwable): Boolean {
        var x: Throwable? = t
        while (x != null) {
            if (x is SQLException && x.sqlState == "23505") return true
            val name = x::class.java.name
            if (name == "org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException") {
                val m = x.message ?: return false
                if (m.contains("Unique", ignoreCase = true) || m.contains("unique", ignoreCase = true)) return true
            }
            x = x.cause
        }
        return false
    }

    private fun updateBestNub(steamid: String, mapName: String, recordId: Uuid, timeMs: Long): Boolean =
        updateBestTable(
            table = BestNubRecordsTable,
            steamidCol = BestNubRecordsTable.playerSteamid,
            mapNameCol = BestNubRecordsTable.mapName,
            recordIdCol = BestNubRecordsTable.recordId,
            steamid = steamid, mapName = mapName, recordId = recordId, timeMs = timeMs,
        )

    private fun updateBestPro(steamid: String, mapName: String, recordId: Uuid, timeMs: Long): Boolean =
        updateBestTable(
            table = BestProRecordsTable,
            steamidCol = BestProRecordsTable.playerSteamid,
            mapNameCol = BestProRecordsTable.mapName,
            recordIdCol = BestProRecordsTable.recordId,
            steamid = steamid, mapName = mapName, recordId = recordId, timeMs = timeMs,
        )

    private fun updateBestTable(
        table: Table,
        steamidCol: Column<String>,
        mapNameCol: Column<String>,
        recordIdCol: Column<Uuid>,
        steamid: String,
        mapName: String,
        recordId: Uuid,
        timeMs: Long,
    ): Boolean {
        val existing = table.selectAll()
            .where { (steamidCol eq steamid) and (mapNameCol eq mapName) }
            .forUpdate()
            .singleOrNull()

        if (existing == null) {
            table.insert {
                it[steamidCol] = steamid
                it[mapNameCol] = mapName
                it[recordIdCol] = recordId
            }
            return true
        }

        val existingTime = MapRecordsTable
            .selectAll()
            .where { MapRecordsTable.id eq existing[recordIdCol] }
            .single()[MapRecordsTable.timeMs]

        if (timeMs < existingTime) {
            table.update({ (steamidCol eq steamid) and (mapNameCol eq mapName) }) {
                it[recordIdCol] = recordId
            }
            return true
        }
        return false
    }

    private fun updateWorldRecord(mapName: String, category: String, recordId: Uuid, timeMs: Long): Boolean {
        val existing = WorldRecordsTable
            .selectAll()
            .where { (WorldRecordsTable.mapName eq mapName) and (WorldRecordsTable.category eq category) }
            .forUpdate()
            .singleOrNull()

        if (existing == null) {
            WorldRecordsTable.insert {
                it[WorldRecordsTable.mapName] = mapName
                it[WorldRecordsTable.category] = category
                it[WorldRecordsTable.recordId] = recordId
            }
            return true
        }

        val existingTime = MapRecordsTable
            .selectAll()
            .where { MapRecordsTable.id eq existing[WorldRecordsTable.recordId] }
            .single()[MapRecordsTable.timeMs]

        if (timeMs < existingTime) {
            WorldRecordsTable.update({ (WorldRecordsTable.mapName eq mapName) and (WorldRecordsTable.category eq category) }) {
                it[WorldRecordsTable.recordId] = recordId
            }
            return true
        }
        return false
    }

    @Suppress("unused")
    private suspend fun emitEvents(result: LeaderboardResult, payload: AddRecordPayload, serverId: Int) {
        eventBus.emit(
            KzEvent.NewRecord(
                result.recordId,
                payload.steamid,
                payload.mapName,
                payload.timeMs,
                payload.checkpoints,
                payload.gochecks,
            ),
        )
        if (result.isWrNub) eventBus.emit(KzEvent.NewWorldRecord(result.recordId, payload.steamid, payload.mapName, payload.timeMs, "nub"))
        if (result.isWrPro) eventBus.emit(KzEvent.NewWorldRecord(result.recordId, payload.steamid, payload.mapName, payload.timeMs, "pro"))

        auditLogger.log("RECORD_SUBMITTED", serverId, buildJsonObject {
            put("record_id", result.recordId.toString())
            put("steamid", payload.steamid)
            put("map_name", payload.mapName)
            put("time_ms", payload.timeMs)
            put("gochecks", payload.gochecks)
            put("checkpoints", payload.checkpoints)
            put("is_pb", result.isPb)
        })
    }
}
