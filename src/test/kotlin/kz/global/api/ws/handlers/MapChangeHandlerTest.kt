package kz.global.api.ws.handlers

import kz.global.api.db.tables.*
import kz.global.api.domain.broadcast.BroadcastService
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
class MapChangeHandlerTest {

    private var serverId = 0
    private var pluginVersionId = 0

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
        transaction {
            serverId = GameServersTable.insert {
                it[name] = "mc-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
            pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
        }
    }

    private fun envelope(map: String) = WsEnvelope(
        msgType = MsgType.MAP_CHANGE,
        data = Json.encodeToJsonElement(MapChangePayload(map)),
    )

    private fun broadcastService(): BroadcastService =
        BroadcastService(ConnectedServersRegistry(), KzEventBus(), scope = CoroutineScope(Dispatchers.Default + Job()))

    @Test
    fun `handle updates session currentMap`() = runTest {
        val handler = MapChangeHandler(broadcastService())
        val (session, _) = mockSession()

        handler.handle(session, envelope("kz_bhop"))

        assertEquals("kz_bhop", session.currentMap)
    }

    @Test
    fun `handle sends MAP_INFO when map has a world record`() = runTest {
        val recordId = uuidV7()
        val srvId = serverId
        val pvId = pluginVersionId
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
                it[checkpoints] = 12
                it[gochecks] = 0
                it[localUid] = "uid-mc"
                it[MapRecordsTable.pluginVersionId] = pvId
            }
            WorldRecordsTable.insert {
                it[mapName] = "kz_canyon"
                it[category] = "pro"
                it[WorldRecordsTable.recordId] = recordId
            }
        }
        val handler = MapChangeHandler(broadcastService())
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_canyon"))

        assertEquals(1, sent().count { it.msgType == MsgType.MAP_INFO })
    }

    @Test
    fun `handle sends no frame when map is not in the database`() = runTest {
        val handler = MapChangeHandler(broadcastService())
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_unknown"))

        assertTrue(sent().isEmpty())
    }
}
