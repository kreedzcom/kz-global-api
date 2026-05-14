package kz.global.api.domain.replays

import kz.global.api.db.tables.MapRecordsTable
import kz.global.api.db.tables.WorldRecordsTable
import kz.global.api.metrics.KzMetrics
import kz.global.api.storage.R2Client
import kotlinx.coroutines.CoroutineDispatcher
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
import java.util.zip.CRC32
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

private class UploadState(val chunks: Array<ByteArray?>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UploadState) return false
        return chunks.contentEquals(other.chunks)
    }

    override fun hashCode(): Int = chunks.contentDeepHashCode()
}

class ReplayService(
    private val r2Client: R2Client,
    private val metrics: KzMetrics,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val log = LoggerFactory.getLogger(ReplayService::class.java)
    private val uploads = java.util.concurrent.ConcurrentHashMap<String, UploadState>()
    private val ZSTD_MAGIC = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())

    fun parseChunk(bytes: ByteArray): ReplayChunk? {
        if (bytes.size < HEADER_SIZE) return null

        val localUid = String(bytes, 0, LOCAL_UID_LEN, Charsets.UTF_8).trimEnd('\u0000')
        val checksum = readUInt32LE(bytes, 72)
        val indexLong = readUInt64LE(bytes, 76)
        val totalLong = readUInt64LE(bytes, 84)
        if (indexLong !in 0..Int.MAX_VALUE.toLong() || totalLong !in 1..Int.MAX_VALUE.toLong()) return null
        val index = indexLong.toInt()
        val total = totalLong.toInt()
        if (total !in (index + 1)..MAX_REPLAY_CHUNKS) return null

        val data = bytes.copyOfRange(HEADER_SIZE, bytes.size)
        return ReplayChunk(localUid, checksum, index, total, data)
    }

    fun receive(chunk: ReplayChunk): ReplayAssemblyResult {
        if (chunk.total !in 1..MAX_REPLAY_CHUNKS || chunk.index < 0 || chunk.index >= chunk.total) {
            uploads.remove(chunk.localUid)
            return ReplayAssemblyResult.Rejected.InvalidChunk
        }

        val state = uploads.getOrPut(chunk.localUid) { UploadState(arrayOfNulls(chunk.total)) }
        if (state.chunks.size != chunk.total) {
            uploads.remove(chunk.localUid)
            return ReplayAssemblyResult.Rejected.InvalidChunk
        }

        val crc = CRC32()
        crc.update(chunk.data)
        if (crc.value != chunk.checksum) {
            log.warn("CRC32 mismatch for chunk {}/{} uid={}", chunk.index, chunk.total, chunk.localUid)
            uploads.remove(chunk.localUid)
            return ReplayAssemblyResult.Rejected.CrcMismatch
        }

        state.chunks[chunk.index] = chunk.data

        if (state.chunks.any { it == null }) {
            return ReplayAssemblyResult.Pending
        }

        uploads.remove(chunk.localUid)

        val totalBytes = state.chunks.sumOf { it!!.size }
        val assembled = ByteArray(totalBytes)
        var pos = 0
        for (part in state.chunks) {
            val b = part!!
            b.copyInto(assembled, pos)
            pos += b.size
        }

        if (!assembled.startsWith(ZSTD_MAGIC)) {
            log.warn("Assembled replay for {} does not start with ZSTD magic", chunk.localUid)
            return ReplayAssemblyResult.Rejected.BadZstdMagic
        }
        return ReplayAssemblyResult.Complete(assembled)
    }

    suspend fun storeReplay(recordId: Uuid, localUid: String, bytes: ByteArray) {
        val r2Key = "replays/$recordId.krpz"
        r2Client.put(r2Key, bytes)

        withContext(ioDispatcher) {
            suspendTransaction {
                MapRecordsTable.update({ MapRecordsTable.localUid eq localUid }) {
                    it[MapRecordsTable.replayR2Key] = r2Key
                }
            }
        }
        metrics.replayUploads.increment()
        log.info("Stored replay for record {} at {}", recordId, r2Key)
    }

    suspend fun getPresignedUrl(mapName: String): String? {
        val r2Key = withContext(ioDispatcher) {
            suspendTransaction {
                (WorldRecordsTable innerJoin MapRecordsTable)
                    .selectAll()
                    .where {
                        (WorldRecordsTable.mapName eq mapName) and
                        (WorldRecordsTable.category eq "nub")
                    }
                    .singleOrNull()
                    ?.get(MapRecordsTable.replayR2Key)
            }
        } ?: return null

        return r2Client.presignedGetUrl(r2Key)
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

}
