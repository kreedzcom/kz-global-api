package kz.global.api.ws.handlers

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kz.global.api.db.tables.*
import kz.global.api.domain.players.PlayerBanService
import kz.global.api.domain.records.RecordService
import kz.global.api.config.R2Config
import kz.global.api.domain.replays.FakeR2Client
import kz.global.api.domain.replays.ReplayService
import kz.global.api.storage.R2Client
import kz.global.api.domain.replays.ReplayServiceTest
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEventBus
import kz.global.api.metrics.KzMetrics
import kz.global.api.support.TestDatabase
import kz.global.api.support.mockSession
import kz.global.api.support.testSecurityConfig
import kz.global.api.support.testWsRateLimiters
import kz.global.api.support.testWsRateLimitersStrictReplayBytes
import kz.global.api.util.uuidV7
import kz.global.api.ws.ConnectedServersRegistry
import kz.global.api.ws.MsgType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplayChunkHandlerTest {

    private val metrics = KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry())
    private lateinit var r2: FakeR2Client
    private lateinit var replayService: ReplayService
    private lateinit var recordService: RecordService
    private lateinit var handler: ReplayChunkHandler

    private val testDispatcher = Dispatchers.Unconfined

    private var serverId = 0
    private var otherServerId = 0
    private var pluginVersionId = 0
    private val steamid = "STEAM_0:0:88888"

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.truncateAll()
        r2 = FakeR2Client()
        replayService = ReplayService(r2, metrics, testSecurityConfig(), ioDispatcher = testDispatcher)
        recordService = RecordService(
            KzEventBus(),
            AuditLogger(),
            metrics,
            PlayerBanService(),
            testSecurityConfig(),
            ioDispatcher = testDispatcher,
        )
        handler = ReplayChunkHandler(
            replayService,
            recordService,
            metrics,
            testWsRateLimiters(),
            ioDispatcher = testDispatcher,
        )

        transaction {
            serverId = GameServersTable.insert {
                it[name] = "replay-handler-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
            otherServerId = GameServersTable.insert {
                it[name] = "other-server"
                it[accessKey] = ByteArray(16) { 7 }
            }[GameServersTable.id]
            pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
            val sid = steamid
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = sid
                it[lastNickname] = "ReplayHandlerPlayer"
            }
        }
    }

    @Test
    fun `handle ignores malformed replay chunk shorter than header`() = runTest {
        val (session, sent) = mockSession(serverId)

        handler.handle(session, ByteArray(91))

        assertTrue(sent().isEmpty())
    }

    @Test
    fun `handle returns early when replay byte rate limit exceeded`() = runTest {
        val strictHandler = ReplayChunkHandler(
            replayService,
            recordService,
            metrics,
            testWsRateLimitersStrictReplayBytes(),
        )
        val (session, sent) = mockSession(serverId)
        val payload = ReplayServiceTest.ZSTD_MAGIC + ByteArray(8)

        strictHandler.handle(session, ReplayServiceTest.buildChunk("uid-rate", payload, 0, 2))
        strictHandler.handle(session, ReplayServiceTest.buildChunk("uid-rate", payload, 1, 2))

        assertTrue(sent().isEmpty())
    }

    @Test
    fun `handle does not ack while replay assembly is pending`() = runTest {
        val (session, sent) = mockSession(serverId)
        val uid = "uid-pending"
        val part = ReplayServiceTest.ZSTD_MAGIC + ByteArray(8)

        handler.handle(session, ReplayServiceTest.buildChunk(uid, part, 0, 2))

        assertTrue(sent().isEmpty())
    }

    @Test
    fun `handle sends FILE_ACK false on CRC mismatch`() = runTest {
        val (session, sent) = mockSession(serverId)
        val uid = "uid-bad-crc"
        val payload = ReplayServiceTest.ZSTD_MAGIC + ByteArray(4)

        handler.handle(session, ReplayServiceTest.buildChunk(uid, payload, 0, 1, badCrc = true))

        val frame = sent().single()
        assertEquals(MsgType.FILE_ACK, frame.msgType)
        assertEquals(uid, frame.data.jsonObject["local_uid"]!!.jsonPrimitive.content)
        assertFalse(frame.data.jsonObject["status"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `handle sends FILE_ACK false when no map_record for this server`() = runTest {
        insertRecord(serverId, "kz_wrong", "uid-no-record")
        val (session, sent) = mockSession(otherServerId)
        val uid = "uid-no-record"
        val payload = ReplayServiceTest.ZSTD_MAGIC + ByteArray(4)

        handler.handle(session, ReplayServiceTest.buildChunk(uid, payload, 0, 1))

        val frame = sent().single()
        assertEquals(MsgType.FILE_ACK, frame.msgType)
        assertFalse(frame.data.jsonObject["status"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `handle stores replay and sends FILE_ACK true on success`() = runTest {
        val recordId = insertRecord(serverId, "kz_ok", "uid-ok")
        val (session, sent) = mockSession(serverId)
        val uid = "uid-ok"
        val payload = ReplayServiceTest.ZSTD_MAGIC + ByteArray(16) { it.toByte() }

        handler.handle(session, ReplayServiceTest.buildChunk(uid, payload, 0, 1))

        val frame = sent().single()
        assertEquals(MsgType.FILE_ACK, frame.msgType)
        assertTrue(frame.data.jsonObject["status"]!!.jsonPrimitive.boolean)
        assertEquals(1, r2.putCalls.size)
        assertEquals("replays/$recordId.krpz", r2.putCalls[0].first)

        val savedKey = transaction {
            MapRecordsTable.selectAll()
                .where { MapRecordsTable.id eq recordId }
                .single()[MapRecordsTable.replayR2Key]
        }
        assertEquals("replays/$recordId.krpz", savedKey)
    }

    @Test
    fun `handle sends FILE_ACK false when R2 store fails`() = runTest {
        val failingR2 = ThrowingR2Client()
        val failingReplay = ReplayService(failingR2, metrics, testSecurityConfig(), ioDispatcher = testDispatcher)
        val failingHandler = ReplayChunkHandler(
            failingReplay,
            recordService,
            metrics,
            testWsRateLimiters(),
            ioDispatcher = testDispatcher,
        )
        insertRecord(serverId, "kz_fail", "uid-store-fail")
        val (session, sent) = mockSession(serverId)
        val payload = ReplayServiceTest.ZSTD_MAGIC + ByteArray(8)

        failingHandler.handle(session, ReplayServiceTest.buildChunk("uid-store-fail", payload, 0, 1))

        val frame = sent().single()
        assertEquals(MsgType.FILE_ACK, frame.msgType)
        assertFalse(frame.data.jsonObject["status"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `handle finalizes pending leaderboard after successful store`() = runTest {
        val replayRequired = ReplayService(
            r2,
            metrics,
            testSecurityConfig(requireReplayForLeaderboard = true),
            ioDispatcher = testDispatcher,
        )
        val records = RecordService(
            KzEventBus(),
            AuditLogger(),
            metrics,
            PlayerBanService(),
            testSecurityConfig(requireReplayForLeaderboard = true),
            ioDispatcher = testDispatcher,
        )
        val pendingHandler = ReplayChunkHandler(
            replayRequired,
            records,
            metrics,
            testWsRateLimiters(),
            ioDispatcher = testDispatcher,
        )
        val recordId = insertRecord(serverId, "kz_pending", "uid-pending-lb", leaderboardPending = true)
        val (session, sent) = mockSession(serverId)
        val payload = ReplayServiceTest.ZSTD_MAGIC + ByteArray(8)

        pendingHandler.handle(session, ReplayServiceTest.buildChunk("uid-pending-lb", payload, 0, 1))

        assertTrue(sent().single().data.jsonObject["status"]!!.jsonPrimitive.boolean)
        transaction {
            assertFalse(
                MapRecordsTable.selectAll()
                    .where { MapRecordsTable.id eq recordId }
                    .single()[MapRecordsTable.leaderboardPending],
            )
            assertEquals(1, BestProRecordsTable.selectAll().count())
        }
    }

    private fun insertRecord(
        srvId: Int,
        map: String,
        localUid: String,
        leaderboardPending: Boolean = false,
    ): Uuid {
        val id = uuidV7()
        val pvId = pluginVersionId
        val sid = steamid
        transaction {
            MapsTable.insertIgnore { it[name] = map }
            MapRecordsTable.insert {
                it[MapRecordsTable.id] = id
                it[MapRecordsTable.serverId] = srvId
                it[playerSteamid] = sid
                it[mapName] = map
                it[timeMs] = 30_000L
                it[checkpoints] = 0
                it[gochecks] = 0
                it[MapRecordsTable.localUid] = localUid
                it[pluginVersionId] = pvId
                it[MapRecordsTable.leaderboardPending] = leaderboardPending
            }
        }
        return id
    }
}

private class ThrowingR2Client : R2Client(
    config = R2Config("https://fake.r2", "key", "secret", "bucket"),
    client = io.mockk.mockk(relaxed = true),
) {
    override suspend fun put(key: String, bytes: ByteArray) {
        throw RuntimeException("r2 down")
    }
}
