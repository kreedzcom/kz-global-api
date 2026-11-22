package kz.global.api.domain.replays

import com.github.luben.zstd.Zstd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KrpReplayValidatorTest {

    private val identity = ReplayIdentity(
        steamId = "STEAM_0:0:42",
        mapName = "kz_canyon",
        checkpoints = 3,
        gochecks = 1,
    )

    @Test
    fun `accepts a header that matches the submitted run`() {
        val compressed = KrpFixtures.compress("STEAM_0:0:42", "kz_canyon", 3, 1)

        val result = KrpReplayValidator.validate(compressed, identity)

        assertIs<KrpValidation.Accepted>(result)
    }

    @Test
    fun `rejects a steam id that does not match`() {
        val compressed = KrpFixtures.compress("STEAM_0:1:99", "kz_canyon", 3, 1)

        val result = KrpReplayValidator.validate(compressed, identity)

        assertRejected(result, KrpRejectReason.STEAM_ID)
    }

    @Test
    fun `rejects a map that does not match`() {
        val compressed = KrpFixtures.compress("STEAM_0:0:42", "kz_other", 3, 1)

        val result = KrpReplayValidator.validate(compressed, identity)

        assertRejected(result, KrpRejectReason.MAP)
    }

    @Test
    fun `rejects checkpoints that do not match`() {
        val compressed = KrpFixtures.compress("STEAM_0:0:42", "kz_canyon", 9, 1)

        val result = KrpReplayValidator.validate(compressed, identity)

        assertRejected(result, KrpRejectReason.CHECKPOINTS)
    }

    @Test
    fun `rejects gochecks that do not match`() {
        val compressed = KrpFixtures.compress("STEAM_0:0:42", "kz_canyon", 3, 4)

        val result = KrpReplayValidator.validate(compressed, identity)

        assertRejected(result, KrpRejectReason.GOCHECKS)
    }

    @Test
    fun `rejects a bad magic`() {
        val compressed = KrpFixtures.compress("STEAM_0:0:42", "kz_canyon", 3, 1, magic = 1L)

        val result = KrpReplayValidator.validate(compressed, identity)

        assertRejected(result, KrpRejectReason.MAGIC)
    }

    @Test
    fun `rejects an unsupported version`() {
        val compressed = KrpFixtures.compress("STEAM_0:0:42", "kz_canyon", 3, 1, version = 2L)

        val result = KrpReplayValidator.validate(compressed, identity)

        assertRejected(result, KrpRejectReason.VERSION)
    }

    @Test
    fun `rejects corrupt sections`() {
        val compressed = KrpFixtures.compress("STEAM_0:0:42", "kz_canyon", 3, 1, sizeData = 0)

        val result = KrpReplayValidator.validate(compressed, identity)

        assertRejected(result, KrpRejectReason.SECTIONS)
    }

    @Test
    fun `rejects a body that is not a replay`() {
        val compressed = Zstd.compress(ByteArray(8))

        val result = KrpReplayValidator.validate(compressed, identity)

        assertRejected(result, KrpRejectReason.TRUNCATED)
    }

    @Test
    fun `rejects bytes that are not zstd`() {
        val result = KrpReplayValidator.validate(byteArrayOf(1, 2, 3, 4), identity)

        assertRejected(result, KrpRejectReason.DECODE)
    }

    private fun assertRejected(result: KrpValidation, reason: String) {
        val rejected = assertIs<KrpValidation.Rejected>(result)
        assertEquals(reason, rejected.reason)
    }
}
