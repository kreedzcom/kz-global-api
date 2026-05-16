package kz.global.api.domain.records

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kz.global.api.db.tables.*
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEventBus
import kz.global.api.metrics.KzMetrics
import kz.global.api.support.TestDatabase
import kz.global.api.ws.AddRecordPayload
import kz.global.api.ws.ConnectedServersRegistry
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*
import kotlin.time.Clock

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordServiceTest {

    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val eventBus = KzEventBus()
    private val metrics = KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry())
    private val service = RecordService(eventBus, auditLogger, metrics)

    private var serverId = 0
    private var pluginVersionId = 0
    private val steamid = "STEAM_0:0:12345"

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.truncateAll()
        transaction {
            serverId = GameServersTable.insert {
                it[name] = "test-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]

            pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]

            val sid = steamid
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = sid
                it[lastNickname] = "TestPlayer"
            }
        }
    }

    // ─── Basic acceptance ────────────────────────────────────────────────────

    @Test
    fun `submit inserts placeholder player when PLAYER_JOIN was not received`() = runTest {
        transaction {
            val sid = steamid
            PlayersTable.deleteWhere { PlayersTable.steamid eq sid }
        }
        val payload = AddRecordPayload(steamid, "kz_canyon", 32_000L, "uid-nojoin", 0, 0)

        val result = service.submit(serverId, pluginVersionId, payload)

        assertIs<RecordResult.Accepted>(result)
        transaction {
            val nick = PlayersTable.selectAll().where { PlayersTable.steamid eq steamid }.single()[PlayersTable.lastNickname]
            assertEquals(steamid, nick)
        }
    }

    @Test
    fun `submit accepts a valid pro run and returns Accepted`() = runTest {
        val payload = AddRecordPayload(steamid, "kz_canyon", 30_000L, "uid-001", 0, 0)

        val result = service.submit(serverId, pluginVersionId, payload)

        assertIs<RecordResult.Accepted>(result)
        assertTrue(result.isPb)
    }

    @Test
    fun `submit accepts a nub run with gochecks`() = runTest {
        val payload = AddRecordPayload(steamid, "kz_canyon", 35_000L, "uid-002", 6, 5)

        val result = service.submit(serverId, pluginVersionId, payload)

        assertIs<RecordResult.Accepted>(result)
        assertTrue(result.isPb)
    }

    @Test
    fun `submit stores checkpoints when gochecks is zero`() = runTest {
        val payload = AddRecordPayload(
            steamid = steamid,
            mapName = "kz_gc_pro",
            timeMs = 22_000L,
            localUid = "uid-gc-pro",
            checkpoints = 5,
            gochecks = 0,
        )

        val result = service.submit(serverId, pluginVersionId, payload)

        assertIs<RecordResult.Accepted>(result)
        transaction {
            assertNotNull(
                WorldRecordsTable.selectAll()
                    .where { (WorldRecordsTable.mapName eq "kz_gc_pro") and (WorldRecordsTable.category eq "pro") }
                    .singleOrNull(),
            )
            assertNull(
                WorldRecordsTable.selectAll()
                    .where { (WorldRecordsTable.mapName eq "kz_gc_pro") and (WorldRecordsTable.category eq "nub") }
                    .singleOrNull(),
            )
            assertNull(
                BestNubRecordsTable.selectAll()
                    .where {
                        (BestNubRecordsTable.playerSteamid eq steamid) and
                            (BestNubRecordsTable.mapName eq "kz_gc_pro")
                    }
                    .singleOrNull(),
            )
            val row = MapRecordsTable.selectAll()
                .where { MapRecordsTable.localUid eq "uid-gc-pro" }
                .single()
            assertEquals(0, row[MapRecordsTable.gochecks])
            assertEquals(5, row[MapRecordsTable.checkpoints])
        }
    }

    @Test
    fun `submit with positive gochecks skips pro world record`() = runTest {
        val payload = AddRecordPayload(
            steamid = steamid,
            mapName = "kz_gc_nub",
            timeMs = 22_000L,
            localUid = "uid-gc-nub",
            checkpoints = 4,
            gochecks = 2,
        )

        val result = service.submit(serverId, pluginVersionId, payload)

        assertIs<RecordResult.Accepted>(result)
        transaction {
            assertNotNull(
                WorldRecordsTable.selectAll()
                    .where { (WorldRecordsTable.mapName eq "kz_gc_nub") and (WorldRecordsTable.category eq "nub") }
                    .singleOrNull(),
            )
            assertNull(
                WorldRecordsTable.selectAll()
                    .where { (WorldRecordsTable.mapName eq "kz_gc_nub") and (WorldRecordsTable.category eq "pro") }
                    .singleOrNull(),
            )
        }
    }

    // ─── Idempotency ─────────────────────────────────────────────────────────

    @Test
    fun `submit is idempotent for duplicate local_uid`() = runTest {
        val payload = AddRecordPayload(steamid, "kz_canyon", 30_000L, "uid-dup", 0, 0)
        val first = service.submit(serverId, pluginVersionId, payload) as RecordResult.Accepted

        val second = service.submit(serverId, pluginVersionId, payload)

        assertIs<RecordResult.Duplicate>(second)
        assertEquals(first.recordId, second.recordId)
    }

    // ─── Anti-cheat (minimum time) ───────────────────────────────────────────

    @Test
    fun `submit rejects time below map minimum`() = runTest {
        transaction {
            MapsTable.insertIgnore { it[name] = "kz_fast" }
            MapMinimumTimesTable.insert {
                it[mapName] = "kz_fast"
                it[minTimeMs] = 10_000L
                it[updatedBy] = "admin"
                it[updatedAt] = Clock.System.now()
            }
        }
        val payload = AddRecordPayload(steamid, "kz_fast", 5_000L, "uid-rejected", 0, 0)

        val result = service.submit(serverId, pluginVersionId, payload)

        assertIs<RecordResult.Rejected>(result)
    }

    @Test
    fun `submit accepts time exactly at map minimum`() = runTest {
        transaction {
            MapsTable.insertIgnore { it[name] = "kz_exact" }
            MapMinimumTimesTable.insert {
                it[mapName] = "kz_exact"
                it[minTimeMs] = 10_000L
                it[updatedBy] = "admin"
                it[updatedAt] = Clock.System.now()
            }
        }
        val payload = AddRecordPayload(steamid, "kz_exact", 10_000L, "uid-exact", 0, 0)

        val result = service.submit(serverId, pluginVersionId, payload)

        assertIs<RecordResult.Accepted>(result)
    }

    // ─── Leaderboard logic ───────────────────────────────────────────────────

    @Test
    fun `pro run sets only pro world record`() = runTest {
        val payload = AddRecordPayload(steamid, "kz_wr", 20_000L, "uid-wr", 0, 0)

        service.submit(serverId, pluginVersionId, payload)

        transaction {
            assertNull(
                WorldRecordsTable.selectAll()
                    .where { (WorldRecordsTable.mapName eq "kz_wr") and (WorldRecordsTable.category eq "nub") }
                    .singleOrNull(),
            )
            assertNotNull(
                WorldRecordsTable.selectAll()
                    .where { (WorldRecordsTable.mapName eq "kz_wr") and (WorldRecordsTable.category eq "pro") }
                    .singleOrNull(),
            )
        }
    }

    @Test
    fun `nub run with gochecks sets only nub world record`() = runTest {
        val payload = AddRecordPayload(steamid, "kz_nub", 40_000L, "uid-nub-wr", 10, 3)

        service.submit(serverId, pluginVersionId, payload)

        transaction {
            assertNotNull(
                WorldRecordsTable.selectAll()
                    .where { (WorldRecordsTable.mapName eq "kz_nub") and (WorldRecordsTable.category eq "nub") }
                    .singleOrNull(),
                "Nub WR should be set",
            )
            assertNull(
                WorldRecordsTable.selectAll()
                    .where { (WorldRecordsTable.mapName eq "kz_nub") and (WorldRecordsTable.category eq "pro") }
                    .singleOrNull(),
                "Pro WR should not be set for a nub run",
            )
        }
    }

    @Test
    fun `faster run replaces world record`() = runTest {
        val slow = AddRecordPayload(steamid, "kz_race", 50_000L, "uid-slow", 0, 0)
        service.submit(serverId, pluginVersionId, slow)
        val steamid2 = "STEAM_0:0:99999"
        transaction {
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = steamid2
                it[lastNickname] = "Faster"
            }
        }
        val fast = AddRecordPayload(steamid2, "kz_race", 30_000L, "uid-fast", 0, 0)

        val fastResult = service.submit(serverId, pluginVersionId, fast) as RecordResult.Accepted

        transaction {
            val wr = WorldRecordsTable.selectAll()
                .where { (WorldRecordsTable.mapName eq "kz_race") and (WorldRecordsTable.category eq "pro") }
                .single()
            assertEquals(fastResult.recordId, wr[WorldRecordsTable.recordId])
        }
    }

    @Test
    fun `slower run does not replace world record`() = runTest {
        val fast = AddRecordPayload(steamid, "kz_hold", 20_000L, "uid-hold-fast", 0, 0)
        val fastResult = service.submit(serverId, pluginVersionId, fast) as RecordResult.Accepted
        val steamid2 = "STEAM_0:0:77777"
        transaction {
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = steamid2
                it[lastNickname] = "Slower"
            }
        }
        val slow = AddRecordPayload(steamid2, "kz_hold", 40_000L, "uid-hold-slow", 0, 0)

        val slowResult = service.submit(serverId, pluginVersionId, slow) as RecordResult.Accepted

        assertTrue(slowResult.isPb) // first run for this player — still a PB
        transaction {
            val wr = WorldRecordsTable.selectAll()
                .where { (WorldRecordsTable.mapName eq "kz_hold") and (WorldRecordsTable.category eq "pro") }
                .single()
            assertEquals(fastResult.recordId, wr[WorldRecordsTable.recordId])
        }
    }

    @Test
    fun `pro run slower than player nub run does not overwrite nub best`() = runTest {
        val nub = AddRecordPayload(steamid, "kz_mix", 20_000L, "uid-mix-nub", 8, 5)
        val nubResult = service.submit(serverId, pluginVersionId, nub) as RecordResult.Accepted
        val pro = AddRecordPayload(steamid, "kz_mix", 25_000L, "uid-mix-pro", 0, 0)

        val proResult = service.submit(serverId, pluginVersionId, pro) as RecordResult.Accepted

        assertTrue(proResult.isPb) // first pro run = PB for pro
        transaction {
            val nubBest = BestNubRecordsTable.selectAll()
                .where {
                    (BestNubRecordsTable.playerSteamid eq steamid) and
                    (BestNubRecordsTable.mapName eq "kz_mix")
                }
                .single()
            assertEquals(nubResult.recordId, nubBest[BestNubRecordsTable.recordId])
        }
    }
}
