package kz.global.api.domain.replays

import kz.global.api.config.SecurityConfig
import kz.global.api.db.tables.BestNubRecordsTable
import kz.global.api.db.tables.BestProRecordsTable
import kz.global.api.db.tables.MapRecordsTable
import kz.global.api.db.tables.WorldRecordsTable
import kz.global.api.domain.records.lockActiveRecordServer
import kz.global.api.domain.records.lockRecordMap
import kz.global.api.metrics.KzMetrics
import kz.global.api.storage.R2Client
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.text.Charsets
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kotlin.time.Duration.Companion.days

// Binary header: packed `ws_uchunk_header` from cs16kz `kz_ws.h` (pragma pack 1), 92 bytes:
//   local_uid[64]:  char[64]
//   id:             uint64   (offset 64, unused on wire)
//   chunk_checksum: int32    CRC32 of chunk data (offset 72)
//   chunk_index:    uint64   0-based (offset 76)
//   chunk_total:    uint64   (offset 84)
private const val HEADER_SIZE = 92
private const val LOCAL_UID_LEN = 64
private const val MAX_REPLAY_CHUNKS = 50_000
const val TOP_REPLAY_COUNT = 10

data class WrReplayUrl(
    val recordId: Uuid,
    val url: String,
    val localUid: String,
    val mapName: String,
    val category: String,
)

data class ReplayChunk(
    val localUid: String,
    val checksum: Long,
    val index: Int,
    val total: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReplayChunk) return false
        return localUid == other.localUid &&
            checksum == other.checksum &&
            index == other.index &&
            total == other.total &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = localUid.hashCode()
        result = 31 * result + checksum.hashCode()
        result = 31 * result + index
        result = 31 * result + total
        result = 31 * result + data.contentHashCode()
        return result
    }
}

private class UploadState(
    val chunks: Array<ByteArray?>,
    val startedAt: kotlin.time.Instant,
) {
    // Count a full 64-bit reference and conservatively allow for each byte array header.
    val indexBytes = 32L + chunks.size * 8L
    var payloadBytes = 0L
    var received = 0
    val retainedBytes get() = indexBytes + payloadBytes + received * 24L
}

class ReplayService(
    private val r2Client: R2Client,
    private val metrics: KzMetrics,
    private val security: SecurityConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.System,
) {

    private val log = LoggerFactory.getLogger(ReplayService::class.java)
    private data class UploadKey(val serverId: Int, val localUid: String)

    private val uploads = mutableMapOf<UploadKey, UploadState>()
    private var retainedBytes = 0L
    private val ZSTD_MAGIC = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())
    private val uploadTtl = security.replayUploadTtlMinutes.minutes

    fun parseChunk(bytes: ByteArray): ReplayChunk? {
        if (bytes.size < HEADER_SIZE) return null

        val localUid = String(bytes, 0, LOCAL_UID_LEN, Charsets.UTF_8).trimEnd('\u0000')
        val checksum = readUInt32LE(bytes, 72)
        val indexLong = readUInt64LE(bytes, 76)
        val totalLong = readUInt64LE(bytes, 84)
        if (indexLong !in 0..Int.MAX_VALUE.toLong() || totalLong !in 1..Int.MAX_VALUE.toLong()) return null
        val index = indexLong.toInt()
        val total = totalLong.toInt()
        if (total !in 1..MAX_REPLAY_CHUNKS || index >= total) return null

        val data = bytes.copyOfRange(HEADER_SIZE, bytes.size)
        return ReplayChunk(localUid, checksum, index, total, data)
    }

    @Synchronized
    fun evictStaleUploads() {
        val cutoff = clock.now() - uploadTtl
        val iterator = uploads.values.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            if (state.startedAt <= cutoff) {
                retainedBytes -= state.retainedBytes
                iterator.remove()
            }
        }
    }

    private fun removeUpload(key: UploadKey) {
        val removed = uploads.remove(key) ?: return
        retainedBytes -= removed.retainedBytes
    }

    @Synchronized
    fun receive(chunk: ReplayChunk, serverId: Int): ReplayAssemblyResult {
        evictStaleUploads()
        val key = UploadKey(serverId, chunk.localUid)
        if (chunk.total !in 1..MAX_REPLAY_CHUNKS || chunk.index !in 0 until chunk.total || chunk.data.isEmpty()) {
            removeUpload(key)
            return ReplayAssemblyResult.Rejected.InvalidChunk
        }

        val existing = uploads[key]
        if (existing != null && existing.chunks.size != chunk.total) {
            removeUpload(key)
            return ReplayAssemblyResult.Rejected.InvalidChunk
        }
        if (existing == null && uploads.keys.count { it.serverId == serverId } >= security.maxConcurrentReplayUploadsPerServer) {
            return ReplayAssemblyResult.Rejected.TooManyConcurrent
        }

        val crc = java.util.zip.CRC32()
        crc.update(chunk.data)
        if (crc.value != chunk.checksum) {
            removeUpload(key)
            return ReplayAssemblyResult.Rejected.CrcMismatch
        }

        val previous = existing?.chunks?.get(chunk.index)
        val increment = chunk.data.size.toLong() - (previous?.size ?: 0)
        val totalBytes = (existing?.payloadBytes ?: 0) + increment
        val metadata = (if (existing == null) 32L + chunk.total * 8L else 0L) + (if (previous == null) 24L else 0L)
        if (totalBytes > security.maxReplayBytes || totalBytes > Int.MAX_VALUE ||
            increment + metadata > security.maxBufferedReplayBytes - retainedBytes
        ) {
            removeUpload(key)
            return ReplayAssemblyResult.Rejected.TooLarge
        }

        val state = existing ?: UploadState(arrayOfNulls(chunk.total), clock.now()).also { uploads[key] = it }
        retainedBytes += increment + metadata
        state.payloadBytes = totalBytes
        if (previous == null) state.received++
        state.chunks[chunk.index] = chunk.data
        if (state.received != state.chunks.size) return ReplayAssemblyResult.Pending

        if (totalBytes > security.maxBufferedReplayBytes - retainedBytes) {
            removeUpload(key)
            return ReplayAssemblyResult.Rejected.TooLarge
        }
        val assembled = ByteArray(totalBytes.toInt())
        var offset = 0
        for (part in state.chunks) {
            val bytes = checkNotNull(part)
            bytes.copyInto(assembled, offset)
            offset += bytes.size
        }
        removeUpload(key)
        if (!assembled.startsWith(ZSTD_MAGIC)) return ReplayAssemblyResult.Rejected.BadZstdMagic

        retainedBytes += totalBytes
        return ReplayAssemblyResult.Complete(assembled) {
            synchronized(this) { retainedBytes -= totalBytes }
        }
    }

    suspend fun stageReplay(recordId: Uuid, bytes: ByteArray): String {
        val key = "replays/$recordId/${Uuid.random()}.krpz"
        r2Client.put(key, bytes)
        metrics.replayUploads.increment()
        return key
    }

    suspend fun attachReplay(recordId: Uuid, key: String, flagged: Boolean): Boolean =
        withContext(ioDispatcher) {
            suspendTransaction {
                if (!lockActiveRecordServer(recordId)) return@suspendTransaction false
                if (!lockRecordMap(recordId)) return@suspendTransaction false
                MapRecordsTable.update({
                    (MapRecordsTable.id eq recordId) and
                        (MapRecordsTable.flagged eq flagged) and
                        (MapRecordsTable.leaderboardPending eq false) and
                        MapRecordsTable.replayR2Key.isNull()
                }) { it[replayR2Key] = key } == 1
            }
        }

    suspend fun discardUnattachedReplay(key: String) {
        try {
            r2Client.delete(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to delete unattached replay {}", key, e)
        }
    }

    fun categoryForGochecks(gochecks: Int): String = if (gochecks == 0) "pro" else "nub"

    suspend fun isRecordInTop10(recordId: Uuid, mapName: String, category: String): Boolean =
        withContext(ioDispatcher) {
            suspendTransaction {
                recordId in findTop10RecordIds(mapName, category)
            }
        }

    suspend fun pruneReplaysOutsideTop10(mapName: String, category: String) {
        val toDelete = withContext(ioDispatcher) {
            suspendTransaction {
                val top10Ids = findTop10RecordIds(mapName, category)
                val categoryFilter = if (category == "pro") {
                    MapRecordsTable.gochecks eq 0
                } else {
                    MapRecordsTable.gochecks greater 0
                }

                MapRecordsTable
                    .select(MapRecordsTable.id, MapRecordsTable.replayR2Key)
                    .where {
                        (MapRecordsTable.mapName eq mapName) and
                            categoryFilter and
                            (MapRecordsTable.flagged eq false) and
                            MapRecordsTable.replayR2Key.isNotNull() and
                            (MapRecordsTable.id notInList top10Ids.toList())
                    }
                    .map { row -> row[MapRecordsTable.id] to row[MapRecordsTable.replayR2Key]!! }
            }
        }

        if (toDelete.isEmpty()) return

        var pruned = 0
        for ((recordId, r2Key) in toDelete) {
            runCatching { r2Client.delete(r2Key) }
                .onSuccess {
                    pruned++
                    withContext(ioDispatcher) {
                        suspendTransaction {
                            MapRecordsTable.update({ MapRecordsTable.id eq recordId }) {
                                it[MapRecordsTable.replayR2Key] = null
                            }
                        }
                    }
                }
                .onFailure { log.warn("Failed to delete replay {}: {}", r2Key, it.message) }
        }
        if (pruned > 0) {
            log.info("Pruned {} replays outside top {} for map={} category={}", pruned, TOP_REPLAY_COUNT, mapName, category)
        }
    }

    suspend fun getPresignedUrl(mapName: String, category: String = "nub"): String? {
        val r2Key = findWrReplayKey(mapName, category) ?: return null
        return r2Client.presignedGetUrl(r2Key)
    }

    suspend fun getWrReplayPresignedUrl(mapName: String): WrReplayUrl? = withContext(ioDispatcher) {
        findWrReplayRecord(mapName, "pro")?.let { record ->
            return@withContext WrReplayUrl(
                recordId = record.id,
                url = r2Client.presignedGetUrl(record.r2Key),
                localUid = record.localUid,
                mapName = mapName,
                category = "pro",
            )
        }
        if (proWrExists(mapName)) {
            return@withContext null
        }
        findWrReplayRecord(mapName, "nub")?.let { record ->
            return@withContext WrReplayUrl(
                recordId = record.id,
                url = r2Client.presignedGetUrl(record.r2Key),
                localUid = record.localUid,
                mapName = mapName,
                category = "nub",
            )
        }
        null
    }

    suspend fun getReplayBytes(mapName: String, category: String): ByteArray? {
        val r2Key = findWrReplayKey(mapName, category) ?: return null
        return r2Client.get(r2Key)
    }

    suspend fun getReplayBytesByRecordId(recordId: Uuid): ByteArray? {
        val r2Key = withContext(ioDispatcher) {
            suspendTransaction {
                MapRecordsTable
                    .select(MapRecordsTable.replayR2Key)
                    .where { MapRecordsTable.id eq recordId }
                    .singleOrNull()
                    ?.get(MapRecordsTable.replayR2Key)
            }
        } ?: return null
        return r2Client.get(r2Key)
    }

    private data class WrReplayRecord(val id: Uuid, val r2Key: String, val localUid: String)

    private suspend fun findWrReplayRecord(mapName: String, category: String): WrReplayRecord? =
        withContext(ioDispatcher) {
            suspendTransaction {
                (WorldRecordsTable innerJoin MapRecordsTable)
                    .select(MapRecordsTable.id, MapRecordsTable.replayR2Key, MapRecordsTable.localUid)
                    .where {
                        (WorldRecordsTable.mapName eq mapName) and
                            (WorldRecordsTable.category eq category) and
                            MapRecordsTable.leaderboardEligible() and
                            MapRecordsTable.replayR2Key.isNotNull()
                    }
                    .singleOrNull()
                    ?.let { row ->
                        val key = row[MapRecordsTable.replayR2Key] ?: return@let null
                        WrReplayRecord(row[MapRecordsTable.id], key, row[MapRecordsTable.localUid])
                    }
            }
        }

    private suspend fun proWrExists(mapName: String): Boolean = withContext(ioDispatcher) {
        suspendTransaction {
            (WorldRecordsTable innerJoin MapRecordsTable)
                .select(WorldRecordsTable.recordId)
                .where {
                    (WorldRecordsTable.mapName eq mapName) and
                        (WorldRecordsTable.category eq "pro") and
                        MapRecordsTable.leaderboardEligible()
                }
                .limit(1)
                .any()
        }
    }

    private suspend fun findWrReplayKey(mapName: String, category: String): String? =
        withContext(ioDispatcher) {
            suspendTransaction {
                (WorldRecordsTable innerJoin MapRecordsTable)
                    .selectAll()
                    .where {
                        (WorldRecordsTable.mapName eq mapName) and
                            (WorldRecordsTable.category eq category) and
                            MapRecordsTable.leaderboardEligible()
                    }
                    .singleOrNull()
                    ?.get(MapRecordsTable.replayR2Key)
            }
        }

    suspend fun gcOldReplays(daysOld: Long = 90) {
        log.info("Starting replay GC (>{} days old, non-WR)...", daysOld)
        val cutoff = Clock.System.now().minus(daysOld.days)

        val candidates = withContext(ioDispatcher) {
            suspendTransaction {
                MapRecordsTable
                    .leftJoin(WorldRecordsTable, { MapRecordsTable.id }, { WorldRecordsTable.recordId })
                    .select(MapRecordsTable.id, MapRecordsTable.replayR2Key)
                    .where {
                        MapRecordsTable.replayR2Key.isNotNull() and
                            (MapRecordsTable.flagged eq false) and
                            (MapRecordsTable.createdAt less cutoff) and
                            WorldRecordsTable.recordId.isNull()
                    }
                    .map { row -> row[MapRecordsTable.id] to row[MapRecordsTable.replayR2Key]!! }
                    .distinctBy { it.first }
            }
        }

        var deleted = 0
        candidates.forEach { (_, r2Key) ->
            runCatching { r2Client.delete(r2Key) }.onSuccess { deleted++ }
                .onFailure { log.warn("Failed to delete replay {}: {}", r2Key, it.message) }
        }
        log.info("Replay GC complete: deleted {} replays", deleted)
    }

    private fun readUInt32LE(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFF) or
        ((buf[offset + 1].toLong() and 0xFF) shl 8) or
        ((buf[offset + 2].toLong() and 0xFF) shl 16) or
        ((buf[offset + 3].toLong() and 0xFF) shl 24)

    private fun readUInt64LE(buf: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0..7) result = result or ((buf[offset + i].toLong() and 0xFF) shl (i * 8))
        return result
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    private fun findTop10RecordIds(mapName: String, category: String): Set<Uuid> =
        if (category == "pro") {
            BestProRecordsTable
                .join(MapRecordsTable, JoinType.INNER, BestProRecordsTable.recordId, MapRecordsTable.id)
                .select(BestProRecordsTable.recordId)
                .where { (BestProRecordsTable.mapName eq mapName) and MapRecordsTable.leaderboardEligible() }
                .orderBy(MapRecordsTable.timeMs)
                .limit(TOP_REPLAY_COUNT)
                .map { it[BestProRecordsTable.recordId] }
                .toSet()
        } else {
            BestNubRecordsTable
                .join(MapRecordsTable, JoinType.INNER, BestNubRecordsTable.recordId, MapRecordsTable.id)
                .select(BestNubRecordsTable.recordId)
                .where { (BestNubRecordsTable.mapName eq mapName) and MapRecordsTable.leaderboardEligible() }
                .orderBy(MapRecordsTable.timeMs)
                .limit(TOP_REPLAY_COUNT)
                .map { it[BestNubRecordsTable.recordId] }
                .toSet()
        }

}
