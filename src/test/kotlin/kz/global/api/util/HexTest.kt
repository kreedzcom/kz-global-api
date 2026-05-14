package kz.global.api.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HexTest {

    @Test
    fun `toHex encodes byte array to lowercase hex`() {
        val input = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

        val result = input.toHex()

        assertEquals("deadbeef", result)
    }

    @Test
    fun `toHex pads single-digit values with leading zero`() {
        val input = byteArrayOf(0x00, 0x0F)

        val result = input.toHex()

        assertEquals("000f", result)
    }

    @Test
    fun `toHex on empty array returns empty string`() {
        assertEquals("", ByteArray(0).toHex())
    }

    @Test
    fun `fromHex decodes lowercase hex string`() {
        val input = "deadbeef"

        val result = input.fromHex()

        assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), result)
    }

    @Test
    fun `fromHex decodes uppercase hex string`() {
        val input = "FF00"

        val result = input.fromHex()

        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x00), result)
    }

    @Test
    fun `toHex and fromHex round-trip`() {
        val original = ByteArray(16) { it.toByte() }

        val roundTripped = original.toHex().fromHex()

        assertContentEquals(original, roundTripped)
    }

    @Test
    fun `fromHex throws on odd-length string`() {
        val oddLength = "abc"

        assertFailsWith<IllegalArgumentException> { oddLength.fromHex() }
    }

    @Test
    fun `16-byte access-key round-trip produces 32 hex chars`() {
        val key = ByteArray(16) { (it * 17).toByte() }

        val hex = key.toHex()

        assertEquals(32, hex.length)
        assertContentEquals(key, hex.fromHex())
    }
}
