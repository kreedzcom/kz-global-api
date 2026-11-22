package kz.global.api.domain.replays

import com.github.luben.zstd.Zstd
import java.nio.charset.StandardCharsets

data class ReplayIdentity(
    val steamId: String,
    val mapName: String,
    val checkpoints: Int,
    val gochecks: Int,
)

sealed class KrpValidation {
    data object Accepted : KrpValidation()

    data class Rejected(val reason: String) : KrpValidation()
}

object KrpRejectReason {
    const val DECODE = "Replay could not be decoded"
    const val TOO_LARGE = "Replay exceeds size limit"
    const val MAGIC = "Replay magic is invalid"
    const val VERSION = "Replay version is not supported"
    const val TRUNCATED = "Replay is truncated"
    const val SECTIONS = "Replay sections are corrupt"
    const val STEAM_ID = "Replay SteamID does not match"
    const val MAP = "Replay map does not match"
    const val CHECKPOINTS = "Replay checkpoints do not match"
    const val GOCHECKS = "Replay gochecks do not match"
}

/**
 * Structural check of a compressed KRP v1 replay. Confirms the file layout and that the
 * header identifies the same player, map, and checkpoint counts as the submitted run.
 * Frame bytes are not reconstructed.
 */
object KrpReplayValidator {

    private const val HEADER_SIZE = 256
    private const val FRAME_SIZE = 112
    private const val MASK_BLOCKS = 2
    private const val FRAME_EVENT = 0
    private const val FRAME_DELTA = 1
    private const val FRAME_KEYFRAME = 2
    private const val MAX_DECOMPRESSED_BYTES = 100L * 1024 * 1024

    private const val MAGIC = 0x4B52502146494C45L
    private const val VERSION = 1L

    private const val STEAM_ID_OFFSET = 48
    private const val STEAM_ID_LENGTH = 35
    private const val MAP_NAME_OFFSET = 83
    private const val MAP_NAME_LENGTH = 64
    private const val CHECKPOINTS_OFFSET = 151
    private const val TELEPORTS_OFFSET = 155
    private const val SIZE_TYPES_OFFSET = 173
    private const val SIZE_FLAGS_OFFSET = 177
    private const val SIZE_DATA_OFFSET = 181
    private const val SIZE_EVENTS_OFFSET = 185

    fun validate(compressed: ByteArray, identity: ReplayIdentity): KrpValidation {
        val body = decompress(compressed) ?: return KrpValidation.Rejected(decompressReason(compressed))
        val structural = validateStructure(body)
        if (structural != null) {
            return KrpValidation.Rejected(structural)
        }
        return matchHeader(body, identity)
    }

    private fun decompress(compressed: ByteArray): ByteArray? {
        val size = runCatching { Zstd.getFrameContentSize(compressed) }.getOrDefault(-1L)
        if (size <= 0L || size > MAX_DECOMPRESSED_BYTES) {
            return null
        }
        return runCatching { Zstd.decompress(compressed, size.toInt()) }
            .getOrNull()
            ?.takeIf { it.size.toLong() == size }
    }

    private fun decompressReason(compressed: ByteArray): String {
        val size = runCatching { Zstd.getFrameContentSize(compressed) }.getOrDefault(-1L)
        if (size > MAX_DECOMPRESSED_BYTES) {
            return KrpRejectReason.TOO_LARGE
        }
        return KrpRejectReason.DECODE
    }

    private fun validateStructure(body: ByteArray): String? {
        if (body.size < HEADER_SIZE) {
            return KrpRejectReason.TRUNCATED
        }
        val magic = readLongLE(body, 0)
        if (magic != MAGIC) {
            return KrpRejectReason.MAGIC
        }
        if (readLongLE(body, 8) != VERSION) {
            return KrpRejectReason.VERSION
        }

        val sizeTypes = readUIntLE(body, SIZE_TYPES_OFFSET)
        val sizeFlags = readUIntLE(body, SIZE_FLAGS_OFFSET)
        val sizeData = readUIntLE(body, SIZE_DATA_OFFSET)
        val sizeEvents = readUIntLE(body, SIZE_EVENTS_OFFSET)
        val total = HEADER_SIZE.toLong() + sizeTypes + sizeFlags + sizeData + sizeEvents
        if (total > body.size.toLong()) {
            return KrpRejectReason.TRUNCATED
        }
        if (sizeFlags % (MASK_BLOCKS * 8L) != 0L) {
            return KrpRejectReason.SECTIONS
        }

        val frameCount = sizeFlags / (MASK_BLOCKS * 8L)
        if (sizeTypes == 0L || body[HEADER_SIZE].toInt() != FRAME_KEYFRAME) {
            return KrpRejectReason.SECTIONS
        }
        return checkSections(body, sizeTypes, sizeFlags, sizeData, sizeEvents, frameCount)
    }

    private fun checkSections(
        body: ByteArray,
        sizeTypes: Long,
        sizeFlags: Long,
        sizeData: Long,
        sizeEvents: Long,
        frameCount: Long,
    ): String? {
        val typesAt = HEADER_SIZE
        val flagsAt = typesAt + sizeTypes.toInt()
        val blockStream = (sizeFlags / MASK_BLOCKS).toInt()
        var rows = 0L
        var events = 0L
        var dataBytes = 0L

        for (index in 0L until sizeTypes) {
            when (body[typesAt + index.toInt()].toInt() and 0xFF) {
                FRAME_EVENT -> events++
                FRAME_DELTA, FRAME_KEYFRAME -> {
                    if (rows >= frameCount) {
                        return KrpRejectReason.SECTIONS
                    }
                    dataBytes += bitsInFrame(body, flagsAt, blockStream, rows)
                    rows++
                }
                else -> return KrpRejectReason.SECTIONS
            }
        }
        if (rows != frameCount || events != sizeEvents || dataBytes != sizeData) {
            return KrpRejectReason.SECTIONS
        }
        return null
    }

    private fun bitsInFrame(body: ByteArray, flagsAt: Int, blockStream: Int, row: Long): Int {
        var count = 0
        for (block in 0 until MASK_BLOCKS) {
            val value = readLongLE(body, flagsAt + block * blockStream + (row * 8).toInt())
            val mask = if (block == 0) -1L else (1L shl (FRAME_SIZE - 64)) - 1
            count += (value and mask).countOneBits()
        }
        return count
    }

    private fun matchHeader(body: ByteArray, identity: ReplayIdentity): KrpValidation {
        val steamId = cString(body, STEAM_ID_OFFSET, STEAM_ID_LENGTH)
        if (steamId != identity.steamId) {
            return KrpValidation.Rejected(KrpRejectReason.STEAM_ID)
        }
        val mapName = cString(body, MAP_NAME_OFFSET, MAP_NAME_LENGTH)
        if (mapName != identity.mapName) {
            return KrpValidation.Rejected(KrpRejectReason.MAP)
        }
        val checkpoints = readUIntLE(body, CHECKPOINTS_OFFSET)
        if (checkpoints != identity.checkpoints.toLong()) {
            return KrpValidation.Rejected(KrpRejectReason.CHECKPOINTS)
        }
        val gochecks = readUIntLE(body, TELEPORTS_OFFSET)
        if (gochecks != identity.gochecks.toLong()) {
            return KrpValidation.Rejected(KrpRejectReason.GOCHECKS)
        }
        return KrpValidation.Accepted
    }

    private fun cString(body: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && body[end] != 0.toByte()) {
            end++
        }
        return String(body, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun readUIntLE(body: ByteArray, offset: Int): Long {
        return (body[offset].toLong() and 0xFF) or
            ((body[offset + 1].toLong() and 0xFF) shl 8) or
            ((body[offset + 2].toLong() and 0xFF) shl 16) or
            ((body[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readLongLE(body: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((body[offset + index].toLong() and 0xFF) shl (8 * index))
        }
        return value
    }
}
