package kz.global.api.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kz.global.api.db.tables.*
import kz.global.api.support.TestDatabase
import kz.global.api.support.adminAuth
import kz.global.api.support.setupAdminRoutes
import kz.global.api.util.uuidV7
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordsRouteTest {

    private var serverId = 0
    private var pluginVersionId = 0
    private val steamid = "STEAM_0:0:44444"

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.truncateAll()
        transaction {
            serverId = GameServersTable.insert {
                it[name] = "test-server"
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
                it[lastNickname] = "RouteTestPlayer"
            }
        }
    }

    private fun insertRecord(
        map: String = "kz_canyon",
        timeMs: Long = 30_000L,
        teleports: Int = 0,
        flagged: Boolean = false,
    ): kotlin.uuid.Uuid {
        val id = uuidV7()
        // Capture class fields before entering lambdas whose receiver type shadows these names.
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
                it[localUid] = "uid-${id}"
                it[MapRecordsTable.pluginVersionId] = pvId
                it[MapRecordsTable.flagged] = flagged
            }
        }
        return id
    }

    // ─── Authentication ───────────────────────────────────────────────────────

    @Test
    fun `GET records requires admin auth`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/records")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ─── GET /admin/records ───────────────────────────────────────────────────

    @Test
    fun `GET records returns empty list when no records exist`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/records") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `GET records returns all records`() = testApplication {
        setupAdminRoutes()
        insertRecord("kz_a")
        insertRecord("kz_b")

        val response = client.get("/admin/records") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, Json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `GET records with flagged=true returns only flagged records`() = testApplication {
        setupAdminRoutes()
        insertRecord("kz_a", flagged = false)
        insertRecord("kz_b", flagged = true)

        val response = client.get("/admin/records?flagged=true") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, body.size)
        assertTrue(body[0].jsonObject["flagged"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `GET records with map filter returns only matching records`() = testApplication {
        setupAdminRoutes()
        insertRecord("kz_canyon")
        insertRecord("kz_bhop")

        val response = client.get("/admin/records?map=kz_canyon") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, body.size)
        assertEquals("kz_canyon", body[0].jsonObject["map_name"]!!.jsonPrimitive.content)
    }

    // ─── PATCH /admin/records/{id} ────────────────────────────────────────────

    @Test
    fun `PATCH record sets flagged to true`() = testApplication {
        setupAdminRoutes()
        val id = insertRecord(flagged = false)

        val response = client.patch("/admin/records/$id") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"flagged":true}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val isFlagged = transaction {
            MapRecordsTable.selectAll()
                .where { MapRecordsTable.id eq id }
                .single()[MapRecordsTable.flagged]
        }
        assertTrue(isFlagged)
    }

    @Test
    fun `PATCH record sets reviewed to true`() = testApplication {
        setupAdminRoutes()
        val id = insertRecord()

        val response = client.patch("/admin/records/$id") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"reviewed":true}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val reviewed = transaction {
            MapRecordsTable.selectAll()
                .where { MapRecordsTable.id eq id }
                .single()[MapRecordsTable.reviewed]
        }
        assertTrue(reviewed)
    }

    @Test
    fun `PATCH record updates time_ms`() = testApplication {
        setupAdminRoutes()
        val id = insertRecord(timeMs = 50_000L)

        val response = client.patch("/admin/records/$id") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"time_ms":25000}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val time = transaction {
            MapRecordsTable.selectAll()
                .where { MapRecordsTable.id eq id }
                .single()[MapRecordsTable.timeMs]
        }
        assertEquals(25_000L, time)
    }

    @Test
    fun `PATCH record with invalid UUID returns 400`() = testApplication {
        setupAdminRoutes()

        val response = client.patch("/admin/records/not-a-uuid") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"flagged":true}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─── DELETE /admin/records/{id} ───────────────────────────────────────────

    @Test
    fun `DELETE record removes it from the database`() = testApplication {
        setupAdminRoutes()
        val id = insertRecord()

        val response = client.delete("/admin/records/$id") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val count = transaction {
            MapRecordsTable.selectAll()
                .where { MapRecordsTable.id eq id }
                .count()
        }
        assertEquals(0L, count)
    }

    @Test
    fun `DELETE non-existent record returns 404`() = testApplication {
        setupAdminRoutes()

        val response = client.delete("/admin/records/${uuidV7()}") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE record with invalid UUID returns 400`() = testApplication {
        setupAdminRoutes()

        val response = client.delete("/admin/records/bad-uuid") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
