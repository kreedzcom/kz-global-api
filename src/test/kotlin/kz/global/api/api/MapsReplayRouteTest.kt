package kz.global.api.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kz.global.api.db.tables.*
import kz.global.api.domain.replays.FakeR2Client
import kz.global.api.support.TestDatabase
import kz.global.api.support.adminAuth
import kz.global.api.support.setupAdminRoutes
import kz.global.api.util.uuidV7
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MapsReplayRouteTest {

    private lateinit var r2: FakeR2Client
    private var serverId = 0
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

        transaction {
            serverId = GameServersTable.insert {
                it[name] = "maps-replay-server"
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
                it[lastNickname] = "ReplayRoutePlayer"
            }
        }
    }

    @Test
    fun `GET replay rejects unauthenticated requests`() = testApplication {
        setupAdminRoutes(r2Client = r2)

        val response = client.get("/admin/maps/kz_test/replay?category=pro")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET replay returns bytes with inline disposition for WR replay`() = testApplication {
        setupAdminRoutes(r2Client = r2)
        val payload = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x01, 0x02)
        val recordId = insertWrRecord("kz_route", "pro", "replays/kz_route_pro.krpz", payload)

        val response = client.get("/admin/maps/kz_route/replay?category=pro") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("inline", response.headers[HttpHeaders.ContentDisposition])
        assertContentEquals(payload, response.bodyAsBytes())
    }

    @Test
    fun `GET replay returns 404 when WR replay missing`() = testApplication {
        setupAdminRoutes(r2Client = r2)
        transaction { MapsTable.insertIgnore { it[name] = "kz_missing" } }

        val response = client.get("/admin/maps/kz_missing/replay?category=pro") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    private fun insertWrRecord(map: String, category: String, r2Key: String, payload: ByteArray): kotlin.uuid.Uuid {
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
                it[timeMs] = 30_000L
                it[checkpoints] = 0
                it[gochecks] = 0
                it[localUid] = "uid-$map-$category"
                it[replayR2Key] = r2Key
                it[pluginVersionId] = pvId
            }
            WorldRecordsTable.insert {
                it[mapName] = map
                it[WorldRecordsTable.category] = category
                it[WorldRecordsTable.recordId] = id
            }
        }
        r2.getResponses[r2Key] = payload
        return id
    }
}
