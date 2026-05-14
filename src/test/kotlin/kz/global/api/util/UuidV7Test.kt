package kz.global.api.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class UuidV7Test {

    @Test
    fun `uuidV7 has version nibble 7`() {
        val uuid = uuidV7()

        val version = uuid.toLongs { msb, _ -> (msb ushr 12) and 0xF }

        assertEquals(7L, version)
    }

    @Test
    fun `uuidV7 has RFC 4122 variant bits`() {
        val uuid = uuidV7()

        val variant = uuid.toLongs { _, lsb -> (lsb ushr 62) and 0x3 }

        assertEquals(2L, variant)
    }

    @Test
    fun `uuidV7 generates unique values in bulk`() {
        val ids = (1..200).map { uuidV7() }.toSet()

        assertEquals(200, ids.size)
    }

    @Test
    fun `uuidV7 MSBs are non-decreasing across time`() {
        val first = uuidV7()
        Thread.sleep(2)

        val second = uuidV7()

        val firstMs  = first.toLongs  { msb, _ -> msb ushr 16 }
        val secondMs = second.toLongs { msb, _ -> msb ushr 16 }
        assertTrue(secondMs >= firstMs, "Second UUID timestamp should be >= first")
    }

    @Test
    fun `uuidV7 string representation is lexicographically ordered by creation time`() {
        val ids = (1..5).map { i ->
            if (i > 1) Thread.sleep(1)
            uuidV7().toString()
        }

        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `concurrent generation produces no duplicates`() {
        val results = java.util.concurrent.CopyOnWriteArrayList<kotlin.uuid.Uuid>()
        val threads = (1..8).map {
            Thread { repeat(50) { results.add(uuidV7()) } }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(400, results.toSet().size)
    }
}
