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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class MapInfoHandlerTest {

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
                it[name] = "mi-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
            pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
        }
    }

    private fun envelope(map: String, msgId: Long = 0L) = WsEnvelope(
        msgType = MsgType.WANT_MAP_INFO,
        msgId = msgId,
        data = Json.encodeToJsonElement(WantMapInfoPayload(map)),
    )

    private fun broadcastService(): BroadcastService =
        BroadcastService(ConnectedServersRegistry(), KzEventBus(), scope = CoroutineScope(Dispatchers.Default + Job()))

    @Test
    fun `handle sends MAP_INFO when map exists`() = runTest {
        val recordId = uuidV7()
        val srvId = serverId
        val pvId = pluginVersionId
        transaction {
            MapsTable.insertIgnore {
                it[name] = "kz_canyon"
                it[type] = "climb"
                it[lengthTier] = 2
                it[difficulty] = 4
            }
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
                it[localUid] = "uid-mi"
                it[MapRecordsTable.pluginVersionId] = pvId
            }
            WorldRecordsTable.insert {
                it[mapName] = "kz_canyon"
                it[category] = "nub"
                it[WorldRecordsTable.recordId] = recordId
            }
        }
        val handler = MapInfoHandler(broadcastService())
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_canyon", msgId = 7L))

        val frames = sent()
        assertEquals(1, frames.size)
        assertEquals(MsgType.MAP_INFO, frames.single().msgType)
        assertEquals(7L, frames.single().msgId)
        val data = frames.single().data.jsonObject
        assertEquals("1", data["type"]!!.jsonPrimitive.content)
        assertEquals("2", data["length"]!!.jsonPrimitive.content)
        assertEquals("4", data["difficulty"]!!.jsonPrimitive.content)
    }

    @Test
    fun `handle sends ERROR when map is not registered`() = runTest {
        val handler = MapInfoHandler(broadcastService())
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_missing"))

        val frames = sent()
        assertEquals(1, frames.size)
        assertEquals(MsgType.ERROR, frames.single().msgType)
        assertContains(
            frames.single().data.jsonObject["message"]!!.jsonPrimitive.content,
            "kz_missing",
        )
    }
}
