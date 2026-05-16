package kz.global.api.domain.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapMetadataTest {

    @Test
    fun `parseMapType maps known names case-insensitively`() {
        assertEquals(0, MapMetadata.parseMapType("bhop"))
        assertEquals(1, MapMetadata.parseMapType("CLIMB"))
        assertEquals(4, MapMetadata.parseMapType("special"))
    }

    @Test
    fun `parseMapType accepts numeric strings in range`() {
        assertEquals(2, MapMetadata.parseMapType("2"))
        assertEquals(0, MapMetadata.parseMapType("0"))
    }

    @Test
    fun `parseMapType returns null for unknown or out of range`() {
        assertNull(MapMetadata.parseMapType(null))
        assertNull(MapMetadata.parseMapType(""))
        assertNull(MapMetadata.parseMapType("surf"))
        assertNull(MapMetadata.parseMapType("9"))
    }

    @Test
    fun `validateLengthTier accepts 0 through 4`() {
        assertEquals(0, MapMetadata.validateLengthTier(0))
        assertEquals(4, MapMetadata.validateLengthTier(4))
        assertNull(MapMetadata.validateLengthTier(5))
        assertNull(MapMetadata.validateLengthTier(null))
    }

    @Test
    fun `validateDifficulty accepts 0 through 9`() {
        assertEquals(0, MapMetadata.validateDifficulty(0))
        assertEquals(9, MapMetadata.validateDifficulty(9))
        assertNull(MapMetadata.validateDifficulty(10))
        assertNull(MapMetadata.validateDifficulty(null))
    }

}
