package kz.global.api.domain.replays

import com.github.luben.zstd.Zstd
import java.nio.ByteBuffer
import java.nio.ByteOrder

object KrpFixtures {

    private const val HEADER_SIZE = 256
    private const val MAGIC = 0x4B52502146494C45L

    fun compress(
        steamId: String,
        mapName: String,
        checkpoints: Int,
        gochecks: Int,
        magic: Long = MAGIC,
        version: Long = 1L,
        sizeData: Int = 112,
    ): ByteArray = Zstd.compress(body(steamId, mapName, checkpoints, gochecks, magic, version, sizeData))

    fun body(
        steamId: String,
        mapName: String,
        checkpoints: Int,
        gochecks: Int,
        magic: Long = MAGIC,
        version: Long = 1L,
        sizeData: Int = 112,
    ): ByteArray {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putLong(magic)
        header.putLong(version)
        header.position(48)
        putCString(header, steamId)
        header.position(83)
        putCString(header, mapName)
        header.position(151)
        header.putInt(checkpoints)
        header.putInt(gochecks)
        header.position(173)
        header.putInt(1)
        header.putInt(16)
        header.putInt(sizeData)
        header.putInt(0)

        val types = byteArrayOf(2)
        val flags = ByteArray(16) { 0xFF.toByte() }
        val data = ByteArray(112)
        return header.array() + types + flags + data
    }

    private fun putCString(buffer: ByteBuffer, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        buffer.put(bytes, 0, bytes.size)
    }
}
