package kz.global.api.ws.handlers

import kz.global.api.db.tables.*
import kz.global.api.domain.broadcast.BroadcastService
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEventBus
import kz.global.api.support.TestDatabase
import kz.global.api.support.mockSession
import kz.global.api.util.uuidV7
import kz.global.api.ws.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelloHandlerTest {

    private val validChecksum = ByteArray(16) { (it + 1).toByte() }
    private val validChecksumHex = validChecksum.joinToString("") { "%02x".format(it) }

    private var serverId = 0

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.truncateAll()
        transaction {
            serverId = GameServersTable.insert {
                it[name] = "hello-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
        }
    }

    private fun handler() = HelloHandler(
        broadcastService = BroadcastService(
            ConnectedServersRegistry(),
            KzEventBus(),
            scope = CoroutineScope(Dispatchers.Default + Job()),
        ),
        auditLogger = AuditLogger(),
    )

    private fun insertPluginVersion(semver: String, checksum: ByteArray, isCutoff: Boolean = false): Int =
        transaction {
            PluginVersionsTable.insert {
                it[PluginVersionsTable.semver] = semver
                it[checksumLinux] = checksum
                it[checksumWindows] = ByteArray(16)
                it[PluginVersionsTable.isCutoff] = isCutoff
            }[PluginVersionsTable.id]
        }

    private fun envelope(version: String, checksum: String, map: String, msgId: Long = 0L) = WsEnvelope(
        msgType = MsgType.HELLO,
        msgId = msgId,
        data = Json.encodeToJsonElement(HelloPayload(version, checksum, map)),
    )

    @Test
    fun `handle sends HELLO_ACK for valid plugin version and checksum`() = runTest {
        insertPluginVersion("1.0.0", validChecksum)
        val (session, sent) = mockSession()

        handler().handle(session, envelope("1.0.0", validChecksumHex, "kz_canyon"))

        assertEquals(MsgType.HELLO_ACK, sent().single().msgType)
    }

    @Test
    fun `handle echoes msgId in HELLO_ACK`() = runTest {
        insertPluginVersion("1.0.0", validChecksum)
        val (session, sent) = mockSession()

        handler().handle(session, envelope("1.0.0", validChecksumHex, "kz_canyon", msgId = 42L))

        assertEquals(42L, sent().single().msgId)
    }

    @Test
    fun `handle sets currentMap on the session`() = runTest {
        insertPluginVersion("1.0.0", validChecksum)
        val (session, _) = mockSession()

        handler().handle(session, envelope("1.0.0", validChecksumHex, "kz_bhop"))

        assertEquals("kz_bhop", session.currentMap)
    }

    @Test
    fun `handle sends nothing when checksum hex is invalid`() = runTest {
        val (session, sent) = mockSession()

        handler().handle(session, envelope("1.0.0", "not-valid-hex!!!", "kz_canyon"))

        assertTrue(sent().isEmpty())
    }

    @Test
    fun `handle sends nothing when checksum hex decodes to wrong number of bytes`() = runTest {
        val (session, sent) = mockSession()

        handler().handle(session, envelope("1.0.0", "aabbcc", "kz_canyon"))

        assertTrue(sent().isEmpty())
    }

    @Test
    fun `handle sends nothing when plugin version is not registered`() = runTest {
        val (session, sent) = mockSession()

        handler().handle(session, envelope("9.9.9", validChecksumHex, "kz_canyon"))

        assertTrue(sent().isEmpty())
    }

    @Test
    fun `handle sends nothing when plugin version checksum does not match`() = runTest {
        insertPluginVersion("1.0.0", validChecksum)
        val wrongChecksumHex = ByteArray(16) { 0xFF.toByte() }.joinToString("") { "%02x".format(it) }
        val (session, sent) = mockSession()

        handler().handle(session, envelope("1.0.0", wrongChecksumHex, "kz_canyon"))

        assertTrue(sent().isEmpty())
    }

    @Test
    fun `handle sends nothing when plugin version is cutoff`() = runTest {
        insertPluginVersion("0.9.0", validChecksum, isCutoff = true)
        val (session, sent) = mockSession()

        handler().handle(session, envelope("0.9.0", validChecksumHex, "kz_canyon"))

        assertTrue(sent().isEmpty())
    }

    @Test
    fun `handle includes map info in HELLO_ACK when known map has a WR`() = runTest {
        insertPluginVersion("1.0.0", validChecksum)
        val srvId = serverId
        val pvId = insertPluginVersion("1.0.0-bis", ByteArray(16))
        val recordId = uuidV7()
        transaction {
            MapsTable.insertIgnore { it[name] = "kz_canyon" }
            PlayersTable.upsert(PlayersTable.steamid) {
                it[steamid] = "STEAM_0:0:1"
                it[lastNickname] = "WrHolder"
            }
            MapRecordsTable.insert {
                it[MapRecordsTable.id] = recordId
                it[MapRecordsTable.serverId] = srvId
                it[playerSteamid] = "STEAM_0:0:1"
                it[mapName] = "kz_canyon"
                it[timeMs] = 28_000L
                it[localUid] = "uid-hello"
                it[MapRecordsTable.pluginVersionId] = pvId
            }
            WorldRecordsTable.insert {
                it[mapName] = "kz_canyon"
                it[category] = "pro"
                it[WorldRecordsTable.recordId] = recordId
            }
        }
        val (session, sent) = mockSession()

        handler().handle(session, envelope("1.0.0", validChecksumHex, "kz_canyon"))

        assertEquals(MsgType.HELLO_ACK, sent().single().msgType)
    }
}
