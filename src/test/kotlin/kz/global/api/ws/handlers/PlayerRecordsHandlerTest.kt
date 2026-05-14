package kz.global.api.ws.handlers

import kz.global.api.db.tables.*
import kz.global.api.support.TestDatabase
import kz.global.api.support.mockSession
import kz.global.api.util.uuidV7
import kz.global.api.ws.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
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
class PlayerRecordsHandlerTest {

    private val handler = PlayerRecordsHandler()

    private var serverId = 0
    private var pluginVersionId = 0
    private val steamid = "STEAM_0:0:55555"

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.truncateAll()
        transaction {
            serverId = GameServersTable.insert {
                it[name] = "pr-server"
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
                it[lastNickname] = "RecordsPlayer"
            }
        }
    }

    private fun envelope(map: String, msgId: Long = 0L) = WsEnvelope(
        msgType = MsgType.WANT_PLAYER_RECORDS,
        msgId = msgId,
        data = Json.encodeToJsonElement(WantPlayerRecordsPayload(steamid, map)),
    )

    private fun insertRecord(map: String, timeMs: Long, teleports: Int, localUid: String, flagged: Boolean = false) {
        val id = uuidV7()
        val srvId = serverId
        val pvId = pluginVersionId
        val sid = steamid
        transaction {
            MapsTable.insertIgnore { it[name] = map }
            MapRecordsTable.insert {
                it[MapRecordsTable.id] = id
                it[MapRecordsTable.serverId] = srvId
                it[playerSteamid] = sid
                it[mapName] = map
                it[MapRecordsTable.timeMs] = timeMs
                it[MapRecordsTable.teleports] = teleports
                it[MapRecordsTable.localUid] = localUid
                it[MapRecordsTable.pluginVersionId] = pvId
                it[MapRecordsTable.flagged] = flagged
            }
        }
    }

    @Test
    fun `handle sends PLAYER_RECORDS response`() = runTest {
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_canyon"))

        assertEquals(MsgType.PLAYER_RECORDS, sent().single().msgType)
    }

    @Test
    fun `handle returns empty records list when player has no runs`() = runTest {
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_norecords"))

        val records = sent().single().data.jsonObject["records"]!!.jsonArray
        assertEquals(0, records.size)
    }

    @Test
    fun `handle returns all non-flagged records for the player on the map`() = runTest {
        insertRecord("kz_canyon", 30_000L, 0, "uid-pro")
        insertRecord("kz_canyon", 35_000L, 3, "uid-nub")
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_canyon"))

        val records = sent().single().data.jsonObject["records"]!!.jsonArray
        assertEquals(2, records.size)
    }

    @Test
    fun `handle excludes flagged records`() = runTest {
        insertRecord("kz_canyon", 20_000L, 0, "uid-ok", flagged = false)
        insertRecord("kz_canyon", 25_000L, 0, "uid-flagged", flagged = true)
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_canyon"))

        val records = sent().single().data.jsonObject["records"]!!.jsonArray
        assertEquals(1, records.size)
        assertEquals(20_000L, records[0].jsonObject["time_ms"]!!.jsonPrimitive.long)
    }

    @Test
    fun `handle returns records sorted by time ascending`() = runTest {
        insertRecord("kz_sort", 40_000L, 0, "uid-slow")
        insertRecord("kz_sort", 25_000L, 0, "uid-fast")
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_sort"))

        val records = sent().single().data.jsonObject["records"]!!.jsonArray
        assertEquals(25_000L, records[0].jsonObject["time_ms"]!!.jsonPrimitive.long)
        assertEquals(40_000L, records[1].jsonObject["time_ms"]!!.jsonPrimitive.long)
    }

    @Test
    fun `handle does not return records for a different map`() = runTest {
        insertRecord("kz_other", 30_000L, 0, "uid-other")
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_canyon"))

        val records = sent().single().data.jsonObject["records"]!!.jsonArray
        assertEquals(0, records.size)
    }
}
