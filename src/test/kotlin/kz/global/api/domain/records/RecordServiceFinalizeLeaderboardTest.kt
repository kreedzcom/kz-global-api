package kz.global.api.domain.records

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kz.global.api.db.tables.*
import kz.global.api.domain.players.PlayerBanService
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEventBus
import kz.global.api.metrics.KzMetrics
import kz.global.api.support.TestDatabase
import kz.global.api.support.testSecurityConfig
import kz.global.api.ws.AddRecordPayload
import kz.global.api.ws.ConnectedServersRegistry
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordServiceFinalizeLeaderboardTest {

    private val service = RecordService(
        KzEventBus(),
        mockk(relaxed = true),
        KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
        PlayerBanService(AuditLogger()),
        testSecurityConfig(requireReplayForLeaderboard = true),
    )

    private var serverId = 0
    private var pluginVersionId = 0

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.truncateAll()
        transaction {
            serverId = GameServersTable.insert {
                it[name] = "finalize-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
            pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
        }
    }

    @Test
    fun `finalizePendingLeaderboard applies best table after replay lands`() = runTest {
        val payload = AddRecordPayload("STEAM_0:0:77", "kz_finalize", 25_000L, "uid-finalize", 0, 0)

        val submit = service.submit(serverId, pluginVersionId, payload)
        assertIs<RecordResult.Accepted>(submit)
        assertFalse(submit.isPb)

        transaction {
            assertEquals(0, BestProRecordsTable.selectAll().count())
        }

        val finalized = service.finalizePendingLeaderboard(submit.recordId, "replays/${submit.recordId}.krpz")

        assertTrue(finalized)
        transaction {
            assertFalse(MapRecordsTable.selectAll().where { MapRecordsTable.localUid eq "uid-finalize" }.single()[MapRecordsTable.leaderboardPending])
            assertEquals(1, BestProRecordsTable.selectAll().count())
        }
    }

    @Test
    fun `shared deactivation fences record and replay commits`() = runTest {
        val payload = AddRecordPayload("STEAM_0:0:77", "kz_revoked", 25_000L, "uid-before", 0, 0)
        val before = assertIs<RecordResult.Accepted>(service.submit(serverId, pluginVersionId, payload))
        transaction {
            GameServersTable.update({ GameServersTable.id eq serverId }) { it[active] = false }
        }
        val replayKey = "replays/${before.recordId}.krpz"

        val later = service.submit(serverId, pluginVersionId, payload.copy(localUid = "uid-after"))
        val ranked = service.finalizePendingLeaderboard(before.recordId, replayKey)
        val flagged = service.flagInvalidReplay(before.recordId, "invalid replay", replayKey)

        assertEquals(RecordResult.ServerInactive, later)
        assertFalse(ranked)
        assertFalse(flagged)
        transaction {
            val record = MapRecordsTable.selectAll().single()
            assertTrue(record[MapRecordsTable.leaderboardPending])
            assertFalse(record[MapRecordsTable.flagged])
            assertEquals(null, record[MapRecordsTable.replayR2Key])
        }
    }

}
