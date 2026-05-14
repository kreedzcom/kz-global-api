package kz.global.api.domain.replays

import kz.global.api.db.tables.MapRecordsTable
import kz.global.api.db.tables.WorldRecordsTable
import kz.global.api.events.AuditLogger
import kz.global.api.metrics.KzMetrics
import kz.global.api.storage.R2Client
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.zip.CRC32
import kotlin.uuid.Uuid
import kotlin.time.Duration.Companion.days

// Binary header layout (packed struct ws_uchunk_header, 88 bytes):
//   local_uid[64]:  char[64]  — null-terminated string
//   id:             uint64    — unused legacy field (8 bytes, offset 64)
//   chunk_checksum: uint32    — CRC32 of this chunk's data (4 bytes, offset 72)
//   chunk_index:    uint64    — 0-based (8 bytes, offset 76)
//   chunk_total:    uint64    — total chunks (8 bytes, offset 80, but header is only 88 bytes so it fits)
private const val HEADER_SIZE = 88
private const val LOCAL_UID_LEN = 64

data class ReplayChunk(
    val localUid: String,
    val checksum: Long,
    val index: Int,
    val total: Int,
    val data: ByteArray,
)

private data class UploadState(val chunks: Array<ByteArray?>)

class ReplayService(
    private val r2Client: R2Client,
    private val auditLogger: AuditLogger,
    private val metrics: KzMetrics,
) {

    private val log = LoggerFactory.getLogger(ReplayService::class.java)
    private val uploads = java.util.concurrent.ConcurrentHashMap<String, UploadState>()
    private val ZSTD_MAGIC = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())

    fun parseChunk(bytes: ByteArray): ReplayChunk? {
        if (bytes.size < HEADER_SIZE) return null

        val localUid = String(bytes, 0, LOCAL_UID_LEN).trimEnd('\u0000')
        val checksum = readUInt32LE(bytes, 72)
        val index = readUInt64LE(bytes, 76).toInt()
        val total = readUInt64LE(bytes, 80).toInt()
        val data = bytes.copyOfRange(HEADER_SIZE, bytes.size)

        return ReplayChunk(localUid, checksum, index, total, data)
    }

    fun receive(chunk: ReplayChunk): ByteArray? {
        val state = uploads.getOrPut(chunk.localUid) { UploadState(arrayOfNulls(chunk.total)) }

        val crc = CRC32()
        crc.update(chunk.data)
        if (crc.value != chunk.checksum) {
            log.warn("CRC32 mismatch for chunk {}/{} uid={}", chunk.index, chunk.total, chunk.localUid)
            return null
        }

        state.chunks[chunk.index] = chunk.data

        if (state.chunks.none { it == null }) {
            uploads.remove(chunk.localUid)
            val assembled = state.chunks.fold(ByteArray(0)) { acc, b -> acc + b!! }

            if (!assembled.startsWith(ZSTD_MAGIC)) {
                log.warn("Assembled replay for {} does not start with ZSTD magic", chunk.localUid)
                return null
            }
            return assembled
        }
        return null
    }

    suspend fun storeReplay(recordId: Uuid, localUid: String, bytes: ByteArray) {
        val r2Key = "replays/$recordId.krpz"
        r2Client.put(r2Key, bytes)

        newSuspendedTransaction(Dispatchers.IO) {
            MapRecordsTable.update({ MapRecordsTable.localUid eq localUid }) {
                it[MapRecordsTable.replayR2Key] = r2Key
            }
        }
        metrics.replayUploads.increment()
        log.info("Stored replay for record {} at {}", recordId, r2Key)
    }

    suspend fun getPresignedUrl(mapName: String): String? {
        val r2Key = newSuspendedTransaction(Dispatchers.IO) {
            (WorldRecordsTable innerJoin MapRecordsTable)
                .selectAll()
                .where {
                    (WorldRecordsTable.mapName eq mapName) and
                    (WorldRecordsTable.category eq "nub")
                }
                .singleOrNull()
                ?.get(MapRecordsTable.replayR2Key)
        } ?: return null

        return r2Client.presignedGetUrl(r2Key)
    }

    suspend fun gcOldReplays(daysOld: Long = 90) {
        log.info("Starting replay GC (>{} days old, non-WR)...", daysOld)
        val cutoff = Clock.System.now().minus(daysOld.days)

        val candidates = newSuspendedTransaction(Dispatchers.IO) {
            val wrIds = WorldRecordsTable.selectAll().map { it[WorldRecordsTable.recordId] }.toSet()
            MapRecordsTable
                .selectAll()
                .where { MapRecordsTable.replayR2Key.isNotNull() }
                .filter { row ->
                    row[MapRecordsTable.id] !in wrIds &&
                    row[MapRecordsTable.createdAt] < cutoff
                }
                .mapNotNull { row ->
                    row[MapRecordsTable.id] to row[MapRecordsTable.replayR2Key]!!
                }
        }

        var deleted = 0
        candidates.forEach { (recordId, r2Key) ->
            runCatching { r2Client.delete(r2Key) }.onSuccess { deleted++ }
                .onFailure { log.warn("Failed to delete replay {}: {}", r2Key, it.message) }
        }
        log.info("Replay GC complete: deleted {} replays", deleted)
    }

    private fun readUInt32LE(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFF)) or
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
