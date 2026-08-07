package kz.global.api.ws.handlers

import kz.global.api.db.tables.*
import kz.global.api.domain.replays.FakeR2Client
import kz.global.api.domain.replays.ReplayService
import kz.global.api.metrics.KzMetrics
import kz.global.api.security.WsRateLimiters
import kz.global.api.support.TestDatabase
import kz.global.api.support.mockSession
import kz.global.api.support.testSecurityConfig
import kz.global.api.util.uuidV7
import kz.global.api.ws.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetReplayHandlerTest {

    private lateinit var r2: FakeR2Client
    private lateinit var handler: GetReplayHandler

    private var serverId = 0
    private var pluginVersionId = 0
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.truncateAll()
        r2 = FakeR2Client()
        val metrics = KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry())
        val replayService = ReplayService(r2, metrics, testSecurityConfig())
        handler = GetReplayHandler(replayService, WsRateLimiters(testSecurityConfig()))

        transaction {
            serverId = GameServersTable.insert {
                it[name] = "gr-server"
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
    fun `handle returns presigned URL for pro WR replay`() = runTest {
        insertWrRecord("kz_replay", "pro", "0_00020000_steam_abc", "replays/pro.krpz")
        val (session, sent) = mockSession(1)
        session.currentMap = "kz_replay"

        handler.handle(session, envelope("kz_replay", msgId = 5L))

        val frame = sent().single()
        assertEquals(MsgType.GET_REPLAY_ACK, frame.msgType)
        assertEquals(5L, frame.msgId)
        val ack = json.decodeFromJsonElement(GetReplayAckPayload.serializer(), frame.data)
        assertEquals("0_00020000_steam_abc", ack.localUid)
        assertTrue(ack.url.contains("replays/pro.krpz"))
        assertEquals("kz_replay", ack.mapName)
    }

    @Test
    fun `handle prefers pro WR over nub when both have replays`() = runTest {
        insertWrRecord("kz_both", "nub", "1_00030000_steam_nub", "replays/nub.krpz", gochecks = 2)
        insertWrRecord("kz_both", "pro", "0_00020000_steam_pro", "replays/pro.krpz", gochecks = 0)
        val (session, sent) = mockSession(1)
        session.currentMap = "kz_both"

        handler.handle(session, envelope("kz_both"))

        val ack = json.decodeFromJsonElement(GetReplayAckPayload.serializer(), sent().single().data)
        assertEquals("0_00020000_steam_pro", ack.localUid)
    }

    @Test
    fun `handle returns error when pro WR exists but has no replay`() = runTest {
        val nubId = uuidV7()
        val proId = uuidV7()
        val srvId = serverId
        val pvId = pluginVersionId
        transaction {
            MapsTable.insertIgnore { it[name] = "kz_pro_missing" }
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = "STEAM_0:0:1"
                it[lastNickname] = "Player"
            }
            MapRecordsTable.insert {
                it[MapRecordsTable.id] = nubId
                it[MapRecordsTable.serverId] = srvId
                it[playerSteamid] = "STEAM_0:0:1"
                it[mapName] = "kz_pro_missing"
                it[MapRecordsTable.timeMs] = 30_000L
                it[MapRecordsTable.checkpoints] = 5
                it[MapRecordsTable.gochecks] = 2
                it[MapRecordsTable.localUid] = "1_00030000_steam_nub"
                it[MapRecordsTable.pluginVersionId] = pvId
                it[MapRecordsTable.replayR2Key] = "replays/nub.krpz"
            }
            MapRecordsTable.insert {
                it[MapRecordsTable.id] = proId
                it[MapRecordsTable.serverId] = srvId
                it[playerSteamid] = "STEAM_0:0:1"
                it[mapName] = "kz_pro_missing"
                it[MapRecordsTable.timeMs] = 20_000L
                it[MapRecordsTable.checkpoints] = 0
                it[MapRecordsTable.gochecks] = 0
                it[MapRecordsTable.localUid] = "0_00020000_steam_pro"
                it[MapRecordsTable.pluginVersionId] = pvId
            }
            WorldRecordsTable.insert {
                it[mapName] = "kz_pro_missing"
                it[category] = "nub"
                it[WorldRecordsTable.recordId] = nubId
            }
            WorldRecordsTable.insert {
                it[mapName] = "kz_pro_missing"
                it[category] = "pro"
                it[WorldRecordsTable.recordId] = proId
            }
        }
        val (session, sent) = mockSession(1)
        session.currentMap = "kz_pro_missing"

        handler.handle(session, envelope("kz_pro_missing"))

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }

    @Test
    fun `handle rejects request for map not matching session`() = runTest {
        insertWrRecord("kz_map_a", "pro", "0_00020000_steam_a", "replays/a.krpz")
        val (session, sent) = mockSession(1)
        session.currentMap = "kz_map_b"

        handler.handle(session, envelope("kz_map_a"))

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }

    @Test
    fun `handle returns error when no WR replay exists`() = runTest {
        val (session, sent) = mockSession(1)
        session.currentMap = "kz_noreplay"
        transaction { MapsTable.insertIgnore { it[name] = "kz_noreplay" } }

        handler.handle(session, envelope("kz_noreplay"))

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }

    @Test
    fun `handle rejects invalid map name`() = runTest {
        val (session, sent) = mockSession(1)
        session.currentMap = "bad map!"

        handler.handle(session, envelope("bad map!"))

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }

    private fun envelope(map: String, msgId: Long = 1L) = WsEnvelope(
        msgType = MsgType.GET_REPLAY,
        msgId = msgId,
        data = Json.encodeToJsonElement(GetReplayPayload(map)),
    )

    private fun insertWrRecord(
        map: String,
        category: String,
        localUid: String,
        r2Key: String,
        gochecks: Int = if (category == "pro") 0 else 2,
    ): kotlin.uuid.Uuid {
        val id = uuidV7()
        val srvId = serverId
        val pvId = pluginVersionId
        transaction {
            MapsTable.insertIgnore { it[name] = map }
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = "STEAM_0:0:1"
                it[lastNickname] = "Player"
            }
            MapRecordsTable.insert {
                it[MapRecordsTable.id] = id
                it[MapRecordsTable.serverId] = srvId
                it[playerSteamid] = "STEAM_0:0:1"
                it[mapName] = map
                it[MapRecordsTable.timeMs] = if (category == "pro") 20_000L else 30_000L
                it[MapRecordsTable.checkpoints] = if (gochecks == 0) 0 else 5
                it[MapRecordsTable.gochecks] = gochecks
                it[MapRecordsTable.localUid] = localUid
                it[MapRecordsTable.pluginVersionId] = pvId
                it[MapRecordsTable.replayR2Key] = r2Key
            }
            WorldRecordsTable.insert {
                it[mapName] = map
                it[WorldRecordsTable.category] = category
                it[WorldRecordsTable.recordId] = id
            }
        }
        return id
    }
}
