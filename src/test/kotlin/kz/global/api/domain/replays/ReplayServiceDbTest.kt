package kz.global.api.domain.replays

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kz.global.api.config.R2Config
import kz.global.api.db.tables.*
import kz.global.api.metrics.KzMetrics
import kz.global.api.storage.R2Client
import kz.global.api.support.TestDatabase
import kz.global.api.support.testSecurityConfig
import kz.global.api.util.uuidV7
import kz.global.api.ws.ConnectedServersRegistry
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Minimal fake that records calls without hitting any real S3/R2 endpoint.
 * Avoids the mockk/byte-buddy limitation with final Kotlin classes on Java 25.
 */
class FakeR2Client : R2Client(
    config = R2Config("https://fake.r2", "key", "secret", "bucket"),
    client = io.mockk.mockk(relaxed = true),
) {
    val putCalls = mutableListOf<Pair<String, ByteArray>>()
    val deleteCalls = mutableListOf<String>()
    val presignCalls = mutableListOf<String>()
    val getCalls = mutableListOf<String>()
    val getResponses = mutableMapOf<String, ByteArray>()

    override suspend fun put(key: String, bytes: ByteArray) {
        putCalls += key to bytes
        getResponses[key] = bytes
    }

    override suspend fun get(key: String): ByteArray {
        getCalls += key
        return getResponses[key] ?: ByteArray(0)
    }

    override suspend fun delete(key: String) {
        deleteCalls += key
    }

    override suspend fun presignedGetUrl(key: String): String {
        presignCalls += key
        return "https://r2.example.com/presigned/$key"
    }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplayServiceDbTest {

    private lateinit var r2: FakeR2Client
    private lateinit var service: ReplayService

    private var serverId = 0
    private var pluginVersionId = 0
    private val steamid = "STEAM_0:0:77777"

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.truncateAll()

        r2 = FakeR2Client()
        service = ReplayService(
            r2Client = r2,
            metrics = KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
            security = testSecurityConfig(),
        )

        transaction {
            serverId = GameServersTable.insert {
                it[name] = "replay-server"
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
                it[lastNickname] = "ReplayPlayer"
            }
        }
    }

    // ─── storeReplay ─────────────────────────────────────────────────────────

    @Test
    fun `storeReplay uploads bytes to R2 under replays-slash-uuid-dot-krpz`() = runTest {
        val recordId = insertRecord("kz_store", "uid-store")
        val bytes = ByteArray(100) { it.toByte() }

        service.storeReplay(recordId, "uid-store", bytes)

        assertEquals(1, r2.putCalls.size)
        assertEquals("replays/$recordId.krpz", r2.putCalls[0].first)
        assertContentEquals(bytes, r2.putCalls[0].second)
    }

    @Test
    fun `storeReplay writes r2 key back to map_record`() = runTest {
        val recordId = insertRecord("kz_store2", "uid-store2")

        service.storeReplay(recordId, "uid-store2", ByteArray(10))

        val savedKey = transaction {
            MapRecordsTable.selectAll()
                .where { MapRecordsTable.id eq recordId }
                .single()[MapRecordsTable.replayR2Key]
        }
        assertEquals("replays/$recordId.krpz", savedKey)
    }

    // ─── getPresignedUrl ─────────────────────────────────────────────────────

    @Test
    fun `getPresignedUrl returns null when map has no nub world record`() = runTest {
        transaction { MapsTable.insertIgnore { it[name] = "kz_noreplay" } }

        val url = service.getPresignedUrl("kz_noreplay")

        assertNull(url)
        assertTrue(r2.presignCalls.isEmpty())
    }

    @Test
    fun `getPresignedUrl returns null when nub WR record has no replay uploaded`() = runTest {
        val recordId = insertRecord("kz_noreplay2", "uid-noreplay")
        transaction {
            WorldRecordsTable.insert {
                it[mapName] = "kz_noreplay2"
                it[category] = "nub"
                it[WorldRecordsTable.recordId] = recordId
            }
        }

        val url = service.getPresignedUrl("kz_noreplay2")

        assertNull(url)
    }

    @Test
    fun `getPresignedUrl delegates to r2Client and returns presigned URL`() = runTest {
        val recordId = insertRecord("kz_replay", "uid-replay")
        val r2Key = "replays/$recordId.krpz"
        transaction {
            WorldRecordsTable.insert {
                it[mapName] = "kz_replay"
                it[category] = "nub"
                it[WorldRecordsTable.recordId] = recordId
            }
            MapRecordsTable.update({ MapRecordsTable.id eq recordId }) {
                it[replayR2Key] = r2Key
            }
        }

        val url = service.getPresignedUrl("kz_replay")

        assertNotNull(url)
        assertEquals(listOf(r2Key), r2.presignCalls)
    }

    @Test
    fun `getWrReplayPresignedUrl skips nub when pro WR exists without replay`() = runTest {
        val nubId = insertRecord("kz_pro_no_replay", "uid-nub")
        val proId = insertRecord("kz_pro_no_replay", "uid-pro")
        transaction {
            MapRecordsTable.update({ MapRecordsTable.id eq nubId }) {
                it[gochecks] = 2
                it[replayR2Key] = "replays/nub.krpz"
            }
            MapRecordsTable.update({ MapRecordsTable.id eq proId }) {
                it[gochecks] = 0
            }
            WorldRecordsTable.insert {
                it[mapName] = "kz_pro_no_replay"
                it[category] = "nub"
                it[WorldRecordsTable.recordId] = nubId
            }
            WorldRecordsTable.insert {
                it[mapName] = "kz_pro_no_replay"
                it[category] = "pro"
                it[WorldRecordsTable.recordId] = proId
            }
        }

        val result = service.getWrReplayPresignedUrl("kz_pro_no_replay")

        assertNull(result)
        assertTrue(r2.presignCalls.isEmpty())
    }

    @Test
    fun `getWrReplayPresignedUrl prefers pro WR replay over nub`() = runTest {
        val nubId = insertRecord("kz_both", "uid-nub")
        val proId = insertRecord("kz_both", "uid-pro")
        transaction {
            MapRecordsTable.update({ MapRecordsTable.id eq nubId }) {
                it[gochecks] = 2
                it[replayR2Key] = "replays/nub.krpz"
            }
            MapRecordsTable.update({ MapRecordsTable.id eq proId }) {
                it[gochecks] = 0
                it[replayR2Key] = "replays/pro.krpz"
            }
            WorldRecordsTable.insert {
                it[mapName] = "kz_both"
                it[category] = "nub"
                it[WorldRecordsTable.recordId] = nubId
            }
            WorldRecordsTable.insert {
                it[mapName] = "kz_both"
                it[category] = "pro"
                it[WorldRecordsTable.recordId] = proId
            }
        }

        val result = service.getWrReplayPresignedUrl("kz_both")

        assertNotNull(result)
        assertEquals("pro", result.category)
        assertEquals("uid-pro", result.localUid)
        assertEquals(listOf("replays/pro.krpz"), r2.presignCalls)
    }

    // ─── getReplayBytes ──────────────────────────────────────────────────────

    @Test
    fun `getReplayBytes returns null when no WR replay exists`() = runTest {
        transaction { MapsTable.insertIgnore { it[name] = "kz_no_wr" } }

        val bytes = service.getReplayBytes("kz_no_wr", "pro")

        assertNull(bytes)
        assertTrue(r2.getCalls.isEmpty())
    }

    @Test
    fun `getReplayBytes fetches bytes from R2 for WR replay`() = runTest {
        val recordId = insertRecord("kz_get", "uid-get")
        val payload = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
        val r2Key = "replays/$recordId.krpz"
        transaction {
            WorldRecordsTable.insert {
                it[mapName] = "kz_get"
                it[category] = "pro"
                it[WorldRecordsTable.recordId] = recordId
            }
            MapRecordsTable.update({ MapRecordsTable.id eq recordId }) {
                it[replayR2Key] = r2Key
            }
        }
        r2.getResponses[r2Key] = payload

        val bytes = service.getReplayBytes("kz_get", "pro")

        assertNotNull(bytes)
        assertContentEquals(payload, bytes)
        assertEquals(listOf(r2Key), r2.getCalls)
    }

    @Test
    fun `getReplayBytesByRecordId returns null when record has no replay`() = runTest {
        val recordId = insertRecord("kz_no_record_replay", "uid-no-record-replay")

        val bytes = service.getReplayBytesByRecordId(recordId)

        assertNull(bytes)
        assertTrue(r2.getCalls.isEmpty())
    }

    @Test
    fun `getReplayBytesByRecordId fetches bytes from R2`() = runTest {
        val recordId = insertRecord("kz_record_replay", "uid-record-replay")
        val payload = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x04)
        val r2Key = "replays/$recordId.krpz"
        transaction {
            MapRecordsTable.update({ MapRecordsTable.id eq recordId }) {
                it[replayR2Key] = r2Key
            }
        }
        r2.getResponses[r2Key] = payload

        val bytes = service.getReplayBytesByRecordId(recordId)

        assertNotNull(bytes)
        assertContentEquals(payload, bytes)
        assertEquals(listOf(r2Key), r2.getCalls)
    }

    // ─── gcOldReplays ────────────────────────────────────────────────────────

    @Test
    fun `gcOldReplays deletes replays older than cutoff that are not WRs`() = runTest {
        val oldRecord = insertRecord("kz_gc", "uid-old")
        val r2Key = "replays/$oldRecord.krpz"
        backdateRecord(oldRecord, 100)
        transaction {
            MapRecordsTable.update({ MapRecordsTable.id eq oldRecord }) { it[replayR2Key] = r2Key }
        }

        service.gcOldReplays(daysOld = 90)

        assertEquals(listOf(r2Key), r2.deleteCalls)
    }

    @Test
    fun `gcOldReplays skips records that are the current WR`() = runTest {
        val wrRecord = insertRecord("kz_wr_gc", "uid-wr")
        val r2Key = "replays/$wrRecord.krpz"
        backdateRecord(wrRecord, 100)
        transaction {
            MapRecordsTable.update({ MapRecordsTable.id eq wrRecord }) { it[replayR2Key] = r2Key }
            WorldRecordsTable.insert {
                it[mapName] = "kz_wr_gc"
                it[category] = "pro"
                it[WorldRecordsTable.recordId] = wrRecord
            }
        }

        service.gcOldReplays(daysOld = 90)

        assertTrue(r2.deleteCalls.isEmpty())
    }

    @Test
    fun `gcOldReplays skips flagged records`() = runTest {
        val flaggedRecord = insertRecord("kz_flagged_gc", "uid-flagged-gc")
        val r2Key = "replays/$flaggedRecord.krpz"
        backdateRecord(flaggedRecord, 100)
        transaction {
            MapRecordsTable.update({ MapRecordsTable.id eq flaggedRecord }) {
                it[replayR2Key] = r2Key
                it[flagged] = true
            }
        }

        service.gcOldReplays(daysOld = 90)

        assertTrue(r2.deleteCalls.isEmpty())
    }

    @Test
    fun `gcOldReplays skips records newer than cutoff`() = runTest {
        val newRecord = insertRecord("kz_new_gc", "uid-new")
        transaction {
            MapRecordsTable.update({ MapRecordsTable.id eq newRecord }) {
                it[replayR2Key] = "replays/$newRecord.krpz"
            }
        }

        service.gcOldReplays(daysOld = 90)

        assertTrue(r2.deleteCalls.isEmpty())
    }

    @Test
    fun `gcOldReplays skips records with no replay uploaded`() = runTest {
        val record = insertRecord("kz_nok_gc", "uid-nok")
        backdateRecord(record, 100)

        service.gcOldReplays(daysOld = 90)

        assertTrue(r2.deleteCalls.isEmpty())
    }

    @Test
    fun `pruneReplaysOutsideTop10 deletes R2 objects for records outside top 10`() = runTest {
        val map = "kz_prune"
        val topIds = (1..10).map { i ->
            val player = "STEAM_0:0:top$i"
            val id = insertRecord(map, "uid-top-$i", timeMs = 10_000L + i)
            transaction {
                PlayersTable.upsert(PlayersTable.steamid) {
                    it[PlayersTable.steamid] = player
                    it[lastNickname] = player
                }
                BestProRecordsTable.insert {
                    it[playerSteamid] = player
                    it[mapName] = map
                    it[recordId] = id
                }
                MapRecordsTable.update({ MapRecordsTable.id eq id }) {
                    it[replayR2Key] = "replays/$id.krpz"
                }
            }
            id
        }
        val outsideId = insertRecord(map, "uid-outside", timeMs = 99_000L)
        transaction {
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = "STEAM_0:0:outside"
                it[lastNickname] = "outside"
            }
            BestProRecordsTable.insert {
                it[playerSteamid] = "STEAM_0:0:outside"
                it[mapName] = map
                it[recordId] = outsideId
            }
            MapRecordsTable.update({ MapRecordsTable.id eq outsideId }) {
                it[replayR2Key] = "replays/$outsideId.krpz"
            }
        }

        service.pruneReplaysOutsideTop10(map, "pro")

        assertEquals(listOf("replays/$outsideId.krpz"), r2.deleteCalls)
        transaction {
            topIds.forEach { id ->
                assertEquals("replays/$id.krpz", MapRecordsTable.selectAll().where { MapRecordsTable.id eq id }.single()[MapRecordsTable.replayR2Key])
            }
            assertNull(MapRecordsTable.selectAll().where { MapRecordsTable.id eq outsideId }.single()[MapRecordsTable.replayR2Key])
        }
    }

    @Test
    fun `pruneReplaysOutsideTop10 keeps replay for flagged records outside top 10`() = runTest {
        val map = "kz_flagged_prune"
        val flaggedId = insertRecord(map, "uid-flagged", timeMs = 99_000L)
        val r2Key = "replays/$flaggedId.krpz"
        transaction {
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = "STEAM_0:0:flagged"
                it[lastNickname] = "flagged"
            }
            BestProRecordsTable.insert {
                it[playerSteamid] = "STEAM_0:0:flagged"
                it[mapName] = map
                it[recordId] = flaggedId
            }
            MapRecordsTable.update({ MapRecordsTable.id eq flaggedId }) {
                it[replayR2Key] = r2Key
                it[flagged] = true
            }
        }

        service.pruneReplaysOutsideTop10(map, "pro")

        assertTrue(r2.deleteCalls.isEmpty())
        transaction {
            assertEquals(
                r2Key,
                MapRecordsTable.selectAll().where { MapRecordsTable.id eq flaggedId }.single()[MapRecordsTable.replayR2Key],
            )
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun insertRecord(map: String, localUid: String, timeMs: Long = 30_000L): kotlin.uuid.Uuid {
        val id = uuidV7()
        val srvId = serverId
        val pvId = pluginVersionId
        val sid = steamid
        val recordTimeMs = timeMs
        transaction {
            MapsTable.insertIgnore { it[name] = map }
            MapRecordsTable.insert {
                it[MapRecordsTable.id] = id
                it[MapRecordsTable.serverId] = srvId
                it[playerSteamid] = sid
                it[mapName] = map
                it[MapRecordsTable.timeMs] = recordTimeMs
                it[checkpoints] = 0
                it[gochecks] = 0
                it[MapRecordsTable.localUid] = localUid
                it[pluginVersionId] = pvId
            }
        }
        return id
    }

    private fun backdateRecord(recordId: kotlin.uuid.Uuid, daysAgo: Long) {
        transaction {
            val past = Clock.System.now().minus(daysAgo.days)
            MapRecordsTable.update({ MapRecordsTable.id eq recordId }) {
                it[createdAt] = past
            }
        }
    }
}
