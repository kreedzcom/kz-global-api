package kz.global.api.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordTimeParserTest {

    @Test
    fun `parseToMs returns raw milliseconds for integer string`() {
        assertEquals(30_000L, RecordTimeParser.parseToMs("30000"))
    }

    @Test
    fun `parseToMs parses seconds with decimal`() {
        assertEquals(83_450L, RecordTimeParser.parseToMs("83.45"))
    }

    @Test
    fun `parseToMs parses minutes and seconds`() {
        assertEquals(83_450L, RecordTimeParser.parseToMs("1:23.45"))
    }

    @Test
    fun `parseToMs returns null for blank input`() {
        assertNull(RecordTimeParser.parseToMs("   "))
    }

    @Test
    fun `parseToMs returns null for invalid format`() {
        assertNull(RecordTimeParser.parseToMs("abc"))
    }
}
