package kz.global.api.domain.broadcast

import kz.global.api.db.tables.*
import kz.global.api.events.KzEvent
import kz.global.api.events.KzEventBus
import kz.global.api.support.TestDatabase
import kz.global.api.support.mockSession
import kz.global.api.util.uuidV7
import kz.global.api.ws.ConnectedServersRegistry
import kz.global.api.ws.MsgType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
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
class BroadcastServiceTest {

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
                it[name] = "bc-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
            pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
        }
    }

    // ─── getMapInfo ──────────────────────────────────────────────────────────

    @Test
    fun `getMapInfo returns null for unknown map`() = runTest {
        val service = service()

        val result = service.getMapInfo("kz_unknown")

        assertNull(result)
    }

    @Test
    fun `getMapInfo returns payload with null WRs when map exists but has no records`() = runTest {
        transaction { MapsTable.insertIgnore { it[name] = "kz_empty" } }
        val service = service()

        val result = service.getMapInfo("kz_empty")

        assertNotNull(result)
        assertEquals("kz_empty", result.mapName)
        assertNull(result.wrNubSteamid)
        assertNull(result.wrNubTimeMs)
        assertNull(result.wrProSteamid)
        assertNull(result.wrProTimeMs)
    }

    @Test
    fun `getMapInfo returns correct nub world record data`() = runTest {
        val recordId = insertRecord("kz_wrs", "STEAM_0:0:1", 30_000L, teleports = 2)
        transaction {
            WorldRecordsTable.insert {
                it[mapName] = "kz_wrs"
                it[category] = "nub"
                it[WorldRecordsTable.recordId] = recordId
            }
        }
        val service = service()

        val result = service.getMapInfo("kz_wrs")

        assertNotNull(result)
        assertEquals("STEAM_0:0:1", result.wrNubSteamid)
        assertEquals(30_000L, result.wrNubTimeMs)
        assertNull(result.wrProSteamid)
    }

    @Test
    fun `getMapInfo returns correct pro world record data`() = runTest {
        val recordId = insertRecord("kz_pro", "STEAM_0:0:2", 25_000L, teleports = 0)
        transaction {
            WorldRecordsTable.insert {
                it[mapName] = "kz_pro"
                it[category] = "pro"
                it[WorldRecordsTable.recordId] = recordId
            }
        }
        val service = service()

        val result = service.getMapInfo("kz_pro")

        assertNotNull(result)
        assertNull(result.wrNubSteamid)
        assertEquals("STEAM_0:0:2", result.wrProSteamid)
        assertEquals(25_000L, result.wrProTimeMs)
    }

    @Test
    fun `getMapInfo returns both nub and pro WR when both exist`() = runTest {
        val nubId = insertRecord("kz_both", "STEAM_0:0:1", 35_000L, teleports = 3)
        val proId = insertRecord("kz_both", "STEAM_0:0:2", 28_000L, teleports = 0, localUid = "uid-pro")
        transaction {
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
        val service = service()

        val result = service.getMapInfo("kz_both")

        assertNotNull(result)
        assertEquals("STEAM_0:0:1", result.wrNubSteamid)
        assertEquals(35_000L, result.wrNubTimeMs)
        assertEquals("STEAM_0:0:2", result.wrProSteamid)
        assertEquals(28_000L, result.wrProTimeMs)
    }

    // ─── WR broadcast ────────────────────────────────────────────────────────
    // A separate CoroutineScope on Dispatchers.Default is used for BroadcastService so
    // that the long-lived subscribeToEvents coroutine doesn't keep the test runner alive.
    // runBlocking is used here (not runTest) because suspendTransaction dispatches on real
    // Dispatchers.IO threads that are not controlled by the test scheduler.

    @Test
    fun `NewWorldRecord event triggers MAP_INFO to sessions on that map`() {
        val recordId = insertRecord("kz_bc", "STEAM_0:0:99", 20_000L, teleports = 0)
        transaction {
            WorldRecordsTable.insert {
                it[mapName] = "kz_bc"
                it[category] = "pro"
                it[WorldRecordsTable.recordId] = recordId
            }
        }

        val registry = ConnectedServersRegistry()
        val (session1, sent1) = mockSession(1)
        val (session2, sent2) = mockSession(2)
        session1.currentMap = "kz_bc"
        session2.currentMap = "kz_other"
        registry.register(session1)
        registry.register(session2)

        val serviceScope = CoroutineScope(Dispatchers.Default + Job())
        val bus = KzEventBus()
        BroadcastService(registry, bus, serviceScope)

        runBlocking {
            bus.emit(KzEvent.NewWorldRecord(recordId, "STEAM_0:0:99", "kz_bc", 20_000L, "pro"))
            delay(500)
        }

        serviceScope.cancel()

        assertEquals(1, sent1().count { it.msgType == MsgType.MAP_INFO }, "session on map should receive MAP_INFO")
        assertEquals(0, sent2().count { it.msgType == MsgType.MAP_INFO }, "session on other map should not receive MAP_INFO")
    }

    @Test
    fun `NewWorldRecord event does nothing when no sessions are on that map`() {
        val recordId = insertRecord("kz_empty_map", "STEAM_0:0:99", 20_000L, teleports = 0)
        transaction {
            WorldRecordsTable.insert {
                it[mapName] = "kz_empty_map"
                it[category] = "pro"
                it[WorldRecordsTable.recordId] = recordId
            }
        }

        val serviceScope = CoroutineScope(Dispatchers.Default + Job())
        val bus = KzEventBus()
        BroadcastService(ConnectedServersRegistry(), bus, serviceScope)

        runBlocking {
            bus.emit(KzEvent.NewWorldRecord(recordId, "STEAM_0:0:99", "kz_empty_map", 20_000L, "pro"))
            delay(200)
        }

        serviceScope.cancel()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun service() =
        BroadcastService(ConnectedServersRegistry(), KzEventBus(), CoroutineScope(Dispatchers.Default + Job()))

    private fun insertRecord(
        map: String,
        steamid: String,
        timeMs: Long,
        teleports: Int,
        localUid: String = "uid-${map}-${steamid}",
    ): kotlin.uuid.Uuid {
        val id = uuidV7()
        val srvId = serverId
        val pvId = pluginVersionId
        transaction {
            MapsTable.insertIgnore { it[name] = map }
            val sid = steamid
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = sid
                it[lastNickname] = "Player"
            }
            MapRecordsTable.insert {
                it[MapRecordsTable.id] = id
                it[MapRecordsTable.serverId] = srvId
                it[playerSteamid] = sid
                it[mapName] = map
                it[MapRecordsTable.timeMs] = timeMs
                it[MapRecordsTable.teleports] = teleports
                it[MapRecordsTable.localUid] = localUid
                it[MapRecordsTable.pluginVersionId] = pvId
            }
        }
        return id
    }
}
