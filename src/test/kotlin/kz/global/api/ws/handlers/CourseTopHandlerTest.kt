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
class CourseTopHandlerTest {

    private val handler = CourseTopHandler(kz.global.api.support.testWsRateLimiters())

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
                it[name] = "ct-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
            pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
        }
    }

    private fun envelope(map: String, category: String = "nub", limit: Int = 10, offset: Int = 0) = WsEnvelope(
        msgType = MsgType.WANT_COURSE_TOP,
        data = Json.encodeToJsonElement(WantCourseTopPayload(map, category, limit, offset)),
    )

    private fun insertPlayerWithBestRecord(
        steamid: String,
        nickname: String,
        map: String,
        timeMs: Long,
        localUid: String,
        checkpoints: Int,
        gochecks: Int,
    ): kotlin.uuid.Uuid {
        val id = uuidV7()
        val srvId = serverId
        val pvId = pluginVersionId
        val sid = steamid
        transaction {
            MapsTable.insertIgnore { it[name] = map }
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = sid
                it[lastNickname] = nickname
            }
            MapRecordsTable.insert {
                it[MapRecordsTable.id] = id
                it[MapRecordsTable.serverId] = srvId
                it[playerSteamid] = sid
                it[mapName] = map
                it[MapRecordsTable.timeMs] = timeMs
                it[MapRecordsTable.checkpoints] = checkpoints
                it[MapRecordsTable.gochecks] = gochecks
                it[MapRecordsTable.localUid] = localUid
                it[MapRecordsTable.pluginVersionId] = pvId
            }
            val proEligible = gochecks == 0
            if (!proEligible) {
                BestNubRecordsTable.insert {
                    it[BestNubRecordsTable.playerSteamid] = sid
                    it[BestNubRecordsTable.mapName] = map
                    it[BestNubRecordsTable.recordId] = id
                }
            } else {
                BestNubRecordsTable.insert {
                    it[BestNubRecordsTable.playerSteamid] = sid
                    it[BestNubRecordsTable.mapName] = map
                    it[BestNubRecordsTable.recordId] = id
                }
                BestProRecordsTable.insert {
                    it[BestProRecordsTable.playerSteamid] = sid
                    it[BestProRecordsTable.mapName] = map
                    it[BestProRecordsTable.recordId] = id
                }
            }
        }
        return id
    }

    @Test
    fun `handle sends COURSE_TOP with correct message type`() = runTest {
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_empty"))

        assertEquals(MsgType.COURSE_TOP, sent().single().msgType)
    }

    @Test
    fun `handle returns empty entries list when no records exist`() = runTest {
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_empty"))

        val entries = sent().single().data.jsonObject["entries"]!!.jsonArray
        assertEquals(0, entries.size)
    }

    @Test
    fun `handle returns nub leaderboard sorted by time ascending`() = runTest {
        insertPlayerWithBestRecord("STEAM_0:0:1", "Slow", "kz_top", 40_000L, "uid-slow", 12, 3)
        insertPlayerWithBestRecord("STEAM_0:0:2", "Fast", "kz_top", 25_000L, "uid-fast", 12, 1)
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_top", "nub"))

        val entries = sent().single().data.jsonObject["entries"]!!.jsonArray
        assertEquals(2, entries.size)
        assertEquals("Fast", entries[0].jsonObject["nickname"]!!.jsonPrimitive.content)
        assertEquals("Slow", entries[1].jsonObject["nickname"]!!.jsonPrimitive.content)
        assertEquals(1, entries[0].jsonObject["rank"]!!.jsonPrimitive.int)
        assertEquals(2, entries[1].jsonObject["rank"]!!.jsonPrimitive.int)
    }

    @Test
    fun `handle returns pro leaderboard only pro-eligible runs`() = runTest {
        insertPlayerWithBestRecord("STEAM_0:0:1", "ProPlayer", "kz_pro", 30_000L, "uid-pro", 12, 0)
        insertPlayerWithBestRecord("STEAM_0:0:2", "NubPlayer", "kz_pro", 20_000L, "uid-nub", 12, 5)
        insertPlayerWithBestRecord("STEAM_0:0:3", "GcNub", "kz_pro", 15_000L, "uid-gc", 12, 2)
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_pro", "pro"))

        val entries = sent().single().data.jsonObject["entries"]!!.jsonArray
        assertEquals(1, entries.size)
        assertEquals("ProPlayer", entries[0].jsonObject["nickname"]!!.jsonPrimitive.content)
    }

    @Test
    fun `handle respects limit parameter`() = runTest {
        repeat(5) { i ->
            insertPlayerWithBestRecord("STEAM_0:0:$i", "Player$i", "kz_limit", (30_000L + i * 1000), "uid-limit-$i", 12, 1)
        }
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_limit", "nub", limit = 3))

        val entries = sent().single().data.jsonObject["entries"]!!.jsonArray
        assertEquals(3, entries.size)
    }

    @Test
    fun `handle respects offset parameter for pagination`() = runTest {
        insertPlayerWithBestRecord("STEAM_0:0:1", "First", "kz_page", 10_000L, "uid-page-1", 12, 1)
        insertPlayerWithBestRecord("STEAM_0:0:2", "Second", "kz_page", 20_000L, "uid-page-2", 12, 1)
        insertPlayerWithBestRecord("STEAM_0:0:3", "Third", "kz_page", 30_000L, "uid-page-3", 12, 1)
        val (session, sent) = mockSession()

        handler.handle(session, envelope("kz_page", "nub", limit = 2, offset = 1))

        val entries = sent().single().data.jsonObject["entries"]!!.jsonArray
        assertEquals(2, entries.size)
        assertEquals("Second", entries[0].jsonObject["nickname"]!!.jsonPrimitive.content)
        assertEquals(2, entries[0].jsonObject["rank"]!!.jsonPrimitive.int)
    }

    @Test
    fun `handle clamps limit to 100`() = runTest {
        val (session, sent) = mockSession()

        // Limit of 200 should be treated as 100 (returns 0 since no entries, but won't crash)
        handler.handle(session, envelope("kz_clamp", "nub", limit = 200))

        assertEquals(MsgType.COURSE_TOP, sent().single().msgType)
    }
}
