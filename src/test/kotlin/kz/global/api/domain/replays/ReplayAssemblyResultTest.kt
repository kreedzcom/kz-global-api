package kz.global.api.domain.replays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class ReplayAssemblyResultTest {

    @Test
    fun `Pending is singleton`() {
        assertSame(ReplayAssemblyResult.Pending, ReplayAssemblyResult.Pending)
    }

    @Test
    fun `Complete equals by content`() {
        val a = ReplayAssemblyResult.Complete(byteArrayOf(1, 2, 3))
        val b = ReplayAssemblyResult.Complete(byteArrayOf(1, 2, 3))
        val c = ReplayAssemblyResult.Complete(byteArrayOf(9))

        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Complete equals is reflexive for same instance`() {
        val a = ReplayAssemblyResult.Complete(byteArrayOf(1))

        assertEquals(a, a)
    }

    @Test
    fun `Rejected variants are distinct`() {
        assertNotEquals(
            ReplayAssemblyResult.Rejected.CrcMismatch as Any,
            ReplayAssemblyResult.Rejected.BadZstdMagic,
        )
        assertNotEquals(
            ReplayAssemblyResult.Rejected.BadZstdMagic as Any,
            ReplayAssemblyResult.Rejected.InvalidChunk,
        )
    }
}
