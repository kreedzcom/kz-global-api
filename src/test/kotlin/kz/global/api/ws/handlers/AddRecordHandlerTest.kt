package kz.global.api.ws.handlers

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kz.global.api.db.tables.*
import kz.global.api.domain.records.RecordService
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEventBus
import kz.global.api.metrics.KzMetrics
import kz.global.api.domain.players.PlayerBanService
import kz.global.api.support.TestDatabase
import kz.global.api.support.testSecurityConfig
import kz.global.api.support.testWsRateLimiters
import kz.global.api.support.mockSession
import kz.global.api.ws.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AddRecordHandlerTest {

    private val recordService = RecordService(
        eventBus = KzEventBus(),
        auditLogger = mockk(relaxed = true),
        metrics = KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
        playerBanService = PlayerBanService(),
        security = testSecurityConfig(),
    )
    private val handler = AddRecordHandler(recordService, testWsRateLimiters())

    private var serverId = 0
    private var pluginVersionId = 0
    private val steamid = "STEAM_0:0:11111"

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.truncateAll()
        transaction {
            serverId = GameServersTable.insert {
                it[name] = "ar-server"
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
                it[lastNickname] = "AddRecordPlayer"
            }
        }
    }

    private fun envelope(payload: AddRecordPayload, msgId: Long = 0L) = WsEnvelope(
        msgType = MsgType.ADD_RECORD,
        msgId = msgId,
        data = Json.encodeToJsonElement(payload),
    )

    @Test
    fun `handle sends RECORD_ACK on accepted run`() = runTest {
        val (session, sent) = mockSession(serverId, pluginVersionId)

        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, "uid-ok", 0, 0)))

        val frame = sent().single()
        assertEquals(MsgType.RECORD_ACK, frame.msgType)
        assertTrue(frame.data.jsonObject["is_pb"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `handle echoes local_uid in RECORD_ACK`() = runTest {
        val (session, sent) = mockSession(serverId, pluginVersionId)

        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, "uid-echo", 0, 0)))

        assertEquals("uid-echo", sent().single().data.jsonObject["local_uid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `handle echoes msgId in RECORD_ACK`() = runTest {
        val (session, sent) = mockSession(serverId, pluginVersionId)

        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, "uid-id", 0, 0), msgId = 77L))

        assertEquals(77L, sent().single().msgId)
    }

    @Test
    fun `handle sends RECORD_ACK with is_pb false on duplicate submission`() = runTest {
        val (session, _) = mockSession(serverId, pluginVersionId)
        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, "uid-dup", 0, 0)))

        val (session2, sent2) = mockSession(serverId, pluginVersionId)
        handler.handle(session2, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, "uid-dup", 0, 0)))

        val frame = sent2().single()
        assertEquals(MsgType.RECORD_ACK, frame.msgType)
        assertFalse(frame.data.jsonObject["is_pb"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `handle sends ERROR on rejected run`() = runTest {
        transaction {
            MapsTable.insert { it[name] = "kz_fast" }
            MapMinimumTimesTable.insert {
                it[mapName] = "kz_fast"
                it[minTimeMs] = 10_000L
                it[updatedBy] = "admin"
                it[updatedAt] = kotlin.time.Clock.System.now()
            }
        }
        val (session, sent) = mockSession(serverId, pluginVersionId)

        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_fast", 1_000L, "uid-rejected", 0, 0)))

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }

    @Test
    fun `handle sends ERROR for negative gochecks`() = runTest {
        val (session, sent) = mockSession(serverId, pluginVersionId)
        val env = WsEnvelope(
            msgType = MsgType.ADD_RECORD,
            data = buildJsonObject {
                put("steamid", steamid)
                put("map_name", "kz_bad")
                put("time_ms", 30_000)
                put("local_uid", "uid-bad-gc")
                put("checkpoints", 5)
                put("gochecks", -1)
            },
        )

        handler.handle(session, env)

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }

    @Test
    fun `handle sends ERROR for checkpoints out of range`() = runTest {
        val (session, sent) = mockSession(serverId, pluginVersionId)
        val env = WsEnvelope(
            msgType = MsgType.ADD_RECORD,
            data = buildJsonObject {
                put("steamid", steamid)
                put("map_name", "kz_bad2")
                put("time_ms", 30_000)
                put("local_uid", "uid-bad-cp")
                put("checkpoints", 70_000)
                put("gochecks", 0)
            },
        )

        handler.handle(session, env)

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }

    @Test
    fun `handle sends ERROR when gochecks positive but checkpoints zero`() = runTest {
        val (session, sent) = mockSession(serverId, pluginVersionId)
        val env = WsEnvelope(
            msgType = MsgType.ADD_RECORD,
            data = buildJsonObject {
                put("steamid", steamid)
                put("map_name", "kz_bad3")
                put("time_ms", 30_000)
                put("local_uid", "uid-bad-pair")
                put("checkpoints", 0)
                put("gochecks", 2)
            },
        )

        handler.handle(session, env)

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }

    @Test
    fun `handle sends ERROR without HELLO`() = runTest {
        val (session, sent) = mockSession(serverId, pluginVersionId = 0)

        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, "uid-no-hello", 0, 0)))

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }
}
