package kz.global.api.domain.records

import kz.global.api.db.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

data class RecordSnapshot(
    val id: Uuid,
    val playerSteamid: String,
    val mapName: String,
    val timeMs: Long,
    val gochecks: Int,
    val localUid: String,
    val flagged: Boolean,
    val replayR2Key: String?,
)

data class RepairResult(
    val mapName: String,
    val recordId: Uuid,
    val localUid: String,
    val previousWrProId: Uuid?,
    val previousWrNubId: Uuid?,
    val newWrProId: Uuid?,
    val newWrNubId: Uuid?,
    val invalidated: Boolean,
) {
    val wrProChanged: Boolean get() = previousWrProId != newWrProId
    val wrNubChanged: Boolean get() = previousWrNubId != newWrNubId
    val wrChanged: Boolean get() = wrProChanged || wrNubChanged
}

class LeaderboardRepairService {

    fun captureWrIds(mapName: String): Pair<Uuid?, Uuid?> =
        wrRecordId(mapName, "pro") to wrRecordId(mapName, "nub")

    /**
     * Rebuilds the affected player PB and both WR categories for the record's map.
     * Must run inside an active transaction after the record mutation.
     *
     * @param previousWrProId WR pro record id before mutation (capture before delete/patch)
     * @param previousWrNubId WR nub record id before mutation
     */
    fun repairForRecord(
        snapshot: RecordSnapshot,
        invalidated: Boolean,
        previousWrProId: Uuid? = captureWrIds(snapshot.mapName).first,
        previousWrNubId: Uuid? = captureWrIds(snapshot.mapName).second,
    ): RepairResult {
        recomputePlayerBest(snapshot.playerSteamid, snapshot.mapName, isPro = true)
        recomputePlayerBest(snapshot.playerSteamid, snapshot.mapName, isPro = false)
        recomputeWorldRecord(snapshot.mapName, "pro")
        recomputeWorldRecord(snapshot.mapName, "nub")

        val (newPro, newNub) = captureWrIds(snapshot.mapName)

        return RepairResult(
            mapName = snapshot.mapName,
            recordId = snapshot.id,
            localUid = snapshot.localUid,
            previousWrProId = previousWrProId,
            previousWrNubId = previousWrNubId,
            newWrProId = newPro,
            newWrNubId = newNub,
            invalidated = invalidated,
        )
    }

    fun recomputePlayerBest(steamid: String, mapName: String, isPro: Boolean) {
        val fastest = findFastestEligible(mapName, steamid, isPro) ?: run {
            if (isPro) {
                BestProRecordsTable.deleteWhere {
                    (BestProRecordsTable.playerSteamid eq steamid) and
                        (BestProRecordsTable.mapName eq mapName)
                }
            } else {
                BestNubRecordsTable.deleteWhere {
                    (BestNubRecordsTable.playerSteamid eq steamid) and
                        (BestNubRecordsTable.mapName eq mapName)
                }
            }
            return
        }

        val recordId = fastest[MapRecordsTable.id]
        if (isPro) {
            val existing = BestProRecordsTable
                .selectAll()
                .where {
                    (BestProRecordsTable.playerSteamid eq steamid) and
                        (BestProRecordsTable.mapName eq mapName)
                }
                .singleOrNull()
            if (existing == null) {
                BestProRecordsTable.insert {
                    it[BestProRecordsTable.playerSteamid] = steamid
                    it[BestProRecordsTable.mapName] = mapName
                    it[BestProRecordsTable.recordId] = recordId
                }
            } else {
                BestProRecordsTable.update({
                    (BestProRecordsTable.playerSteamid eq steamid) and
                        (BestProRecordsTable.mapName eq mapName)
                }) {
                    it[BestProRecordsTable.recordId] = recordId
                }
            }
        } else {
            val existing = BestNubRecordsTable
                .selectAll()
                .where {
                    (BestNubRecordsTable.playerSteamid eq steamid) and
                        (BestNubRecordsTable.mapName eq mapName)
                }
                .singleOrNull()
            if (existing == null) {
                BestNubRecordsTable.insert {
                    it[BestNubRecordsTable.playerSteamid] = steamid
                    it[BestNubRecordsTable.mapName] = mapName
                    it[BestNubRecordsTable.recordId] = recordId
                }
            } else {
                BestNubRecordsTable.update({
                    (BestNubRecordsTable.playerSteamid eq steamid) and
                        (BestNubRecordsTable.mapName eq mapName)
                }) {
                    it[BestNubRecordsTable.recordId] = recordId
                }
            }
        }
    }

    fun recomputeWorldRecord(mapName: String, category: String) {
        val isPro = category == "pro"
        val fastest = findFastestEligible(mapName, playerSteamid = null, isPro = isPro) ?: run {
            WorldRecordsTable.deleteWhere {
                (WorldRecordsTable.mapName eq mapName) and
                    (WorldRecordsTable.category eq category)
            }
            return
        }

        val recordId = fastest[MapRecordsTable.id]
        val existing = WorldRecordsTable
            .selectAll()
            .where {
                (WorldRecordsTable.mapName eq mapName) and
                    (WorldRecordsTable.category eq category)
            }
            .singleOrNull()

        if (existing == null) {
            WorldRecordsTable.insert {
                it[WorldRecordsTable.mapName] = mapName
                it[WorldRecordsTable.category] = category
                it[WorldRecordsTable.recordId] = recordId
            }
        } else {
            WorldRecordsTable.update({
                (WorldRecordsTable.mapName eq mapName) and
                    (WorldRecordsTable.category eq category)
            }) {
                it[WorldRecordsTable.recordId] = recordId
            }
        }
    }

    private fun wrRecordId(mapName: String, category: String): Uuid? =
        WorldRecordsTable
            .selectAll()
            .where {
                (WorldRecordsTable.mapName eq mapName) and
                    (WorldRecordsTable.category eq category)
            }
            .singleOrNull()
            ?.get(WorldRecordsTable.recordId)

    private fun findFastestEligible(
        mapName: String,
        playerSteamid: String?,
        isPro: Boolean,
    ): ResultRow? {
        val gocheckFilter = if (isPro) {
            MapRecordsTable.gochecks eq 0
        } else {
            MapRecordsTable.gochecks greater 0
        }

        var condition: Op<Boolean> =
            (MapRecordsTable.mapName eq mapName) and
                (MapRecordsTable.flagged eq false) and
                gocheckFilter

        if (playerSteamid != null) {
            condition = condition and (MapRecordsTable.playerSteamid eq playerSteamid)
        }

        return MapRecordsTable
            .selectAll()
            .where { condition }
            .orderBy(MapRecordsTable.timeMs)
            .limit(1)
            .singleOrNull()
    }
}
