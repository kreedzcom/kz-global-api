package kz.global.api.ws.handlers

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kz.global.api.db.tables.*
import kz.global.api.domain.records.RecordService
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEventBus
import kz.global.api.metrics.KzMetrics
import kz.global.api.support.TestDatabase
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
    )
    private val handler = AddRecordHandler(recordService)

    private var serverId = 0
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
            PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }
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
        val (session, sent) = mockSession(serverId)

        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, 0, "uid-ok")))

        val frame = sent().single()
        assertEquals(MsgType.RECORD_ACK, frame.msgType)
        assertTrue(frame.data.jsonObject["is_pb"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `handle echoes local_uid in RECORD_ACK`() = runTest {
        val (session, sent) = mockSession(serverId)

        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, 0, "uid-echo")))

        assertEquals("uid-echo", sent().single().data.jsonObject["local_uid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `handle echoes msgId in RECORD_ACK`() = runTest {
        val (session, sent) = mockSession(serverId)

        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, 0, "uid-id"), msgId = 77L))

        assertEquals(77L, sent().single().msgId)
    }

    @Test
    fun `handle sends RECORD_ACK with is_pb false on duplicate submission`() = runTest {
        val (session, _) = mockSession(serverId)
        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, 0, "uid-dup")))

        val (session2, sent2) = mockSession(serverId)
        handler.handle(session2, envelope(AddRecordPayload(steamid, "kz_canyon", 30_000L, 0, "uid-dup")))

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
        val (session, sent) = mockSession(serverId)

        handler.handle(session, envelope(AddRecordPayload(steamid, "kz_fast", 1_000L, 0, "uid-rejected")))

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }
}
