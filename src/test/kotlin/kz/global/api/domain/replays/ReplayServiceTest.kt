package kz.global.api.domain.replays

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kz.global.api.metrics.KzMetrics
import kz.global.api.storage.R2Client
import kz.global.api.support.testSecurityConfig
import kz.global.api.ws.ConnectedServersRegistry
import java.util.zip.CRC32
import kotlin.test.*

class ReplayServiceTest {

    private val service = ReplayService(
        r2Client = mockk(relaxed = true),
        metrics = KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
        security = testSecurityConfig(),
    )

    private val serverId = 1

    // ─── parseChunk ──────────────────────────────────────────────────────────

    @Test
    fun `parseChunk returns null when payload is shorter than header`() {
        assertNull(service.parseChunk(ByteArray(91)))
    }

    @Test
    fun `parseChunk returns null for empty byte array`() {
        assertNull(service.parseChunk(ByteArray(0)))
    }

    @Test
    fun `parseChunk correctly extracts localUid`() {
        val raw = buildChunk("my-local-uid", ByteArray(4), 0, 1)

        val chunk = service.parseChunk(raw)

        assertNotNull(chunk)
        assertEquals("my-local-uid", chunk.localUid)
    }

    @Test
    fun `parseChunk strips null bytes from localUid`() {
        val raw = buildChunk("uid\u0000\u0000", ByteArray(4), 0, 1)

        val chunk = service.parseChunk(raw)

        assertNotNull(chunk)
        assertEquals("uid", chunk.localUid)
    }

    @Test
    fun `parseChunk extracts correct index and total`() {
        val data = ByteArray(10) { it.toByte() }
        val raw = buildChunk("uid", data, index = 3, total = 10)

        val chunk = service.parseChunk(raw)

        assertNotNull(chunk)
        assertEquals(3, chunk.index)
        assertEquals(10, chunk.total)
    }

    @Test
    fun `parseChunk extracts CRC32 checksum`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val expectedCrc = CRC32().also { it.update(data) }.value
        val raw = buildChunk("uid", data, 0, 1)

        val chunk = service.parseChunk(raw)

        assertNotNull(chunk)
        assertEquals(expectedCrc, chunk.checksum)
    }

    @Test
    fun `parseChunk data field is exactly the bytes after the header`() {
        val data = ByteArray(20) { (it + 1).toByte() }
        val raw = buildChunk("uid", data, 0, 1)

        val chunk = service.parseChunk(raw)

        assertNotNull(chunk)
        assertContentEquals(data, chunk.data)
    }

    // ─── receive ─────────────────────────────────────────────────────────────

    @Test
    fun `receive returns CrcMismatch on CRC mismatch`() {
        val data = ByteArray(8) { 0x42 }
        val parsed = service.parseChunk(buildChunk("uid", data, 0, 1, badCrc = true))!!

        val result = service.receive(parsed, serverId)

        assertIs<ReplayAssemblyResult.Rejected.CrcMismatch>(result)
    }

    @Test
    fun `receive returns Pending when not all chunks have arrived`() {
        val data = ByteArray(4) { it.toByte() }
        val chunk = service.parseChunk(buildChunk("multi-uid", data, 0, 3))!!

        val result = service.receive(chunk, serverId)

        assertSame(ReplayAssemblyResult.Pending, result)
    }

    @Test
    fun `receive assembles single chunk with ZSTD magic and returns bytes`() {
        val payload = ZSTD_MAGIC + ByteArray(100) { it.toByte() }
        val parsed = service.parseChunk(buildChunk("single-uid", payload, 0, 1))!!

        val result = service.receive(parsed, serverId)

        val complete = assertIs<ReplayAssemblyResult.Complete>(result)
        assertContentEquals(ZSTD_MAGIC, complete.bytes.take(4).toByteArray())
    }

    @Test
    fun `receive assembles multiple chunks in order`() {
        val uid = "multi-chunk-uid"
        val part0 = ByteArray(50) { 0x11 }
        val part1 = ByteArray(50) { 0x22 }
        val fullExpected = ZSTD_MAGIC + part0 + part1
        val c0 = service.parseChunk(buildChunk(uid, ZSTD_MAGIC + part0, 0, 2))!!
        val c1 = service.parseChunk(buildChunk(uid, part1, 1, 2))!!

        assertSame(ReplayAssemblyResult.Pending, service.receive(c0, serverId))
        val assembled = assertIs<ReplayAssemblyResult.Complete>(service.receive(c1, serverId))

        assertContentEquals(fullExpected, assembled.bytes)
    }

    @Test
    fun `receive returns BadZstdMagic when assembled data does not start with ZSTD magic`() {
        val notZstd = ByteArray(20) { 0xAA.toByte() }
        val parsed = service.parseChunk(buildChunk("bad-magic-uid", notZstd, 0, 1))!!

        val result = service.receive(parsed, serverId)

        assertIs<ReplayAssemblyResult.Rejected.BadZstdMagic>(result)
    }

    @Test
    fun `receive cleans up state so the same uid can be used for a new upload`() {
        val uid = "cleanup-uid"
        val data = ZSTD_MAGIC + ByteArray(10)
        val first = service.parseChunk(buildChunk(uid, data, 0, 1))!!
        assertIs<ReplayAssemblyResult.Complete>(service.receive(first, serverId))

        val second = service.parseChunk(buildChunk(uid, data, 0, 1))!!
        val result = service.receive(second, serverId)

        assertIs<ReplayAssemblyResult.Complete>(result)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    companion object {
        val ZSTD_MAGIC = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())

        /**
         * Builds a raw binary frame matching packed `ws_uchunk_header` from cs16kz `kz_ws.h` (92 bytes) + [data].
         *
         * Layout (all little-endian):
         *   offset  0: local_uid[64]
         *   offset 64: id uint64 (zeros)
         *   offset 72: chunk_checksum int32 / CRC32 of data
         *   offset 76: chunk_index uint64
         *   offset 84: chunk_total uint64
         */
        fun buildChunk(
            localUid: String,
            data: ByteArray,
            index: Int,
            total: Int,
            badCrc: Boolean = false,
        ): ByteArray {
            val crc = CRC32().also { it.update(data) }.value
            val checksum = if (badCrc) crc xor 0xFF else crc

            val header = ByteArray(92)
            val uidBytes = localUid.toByteArray(Charsets.UTF_8)
            uidBytes.copyInto(header, 0, 0, minOf(uidBytes.size, 63))
            writeUInt32LE(header, 72, checksum)
            writeUInt64LE(header, 76, index.toLong())
            writeUInt64LE(header, 84, total.toLong())
            return header + data
        }

        private fun writeUInt32LE(buf: ByteArray, offset: Int, value: Long) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
            buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
            buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        private fun writeUInt64LE(buf: ByteArray, offset: Int, value: Long) {
            for (i in 0..7) {
                buf[offset + i] = ((value shr (i * 8)) and 0xFF).toByte()
            }
        }
    }
}
