package kz.global.api.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kz.global.api.db.tables.MapMinimumTimesTable
import kz.global.api.db.tables.MapsTable
import kz.global.api.support.TestDatabase
import kz.global.api.support.adminAuth
import kz.global.api.support.setupAdminRoutes
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*
import kotlin.time.Clock

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MapTimesRouteTest {

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
    }

    // ─── GET /admin/map-times ─────────────────────────────────────────────────

    @Test
    fun `GET map-times requires admin auth`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/map-times")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET map-times returns empty list when none set`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/map-times") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `GET map-times returns all configured entries`() = testApplication {
        setupAdminRoutes()
        transaction {
            MapsTable.insertIgnore { it[name] = "kz_bhop" }
            MapMinimumTimesTable.insert {
                it[mapName] = "kz_bhop"
                it[minTimeMs] = 15_000L
                it[updatedBy] = "admin"
                it[updatedAt] = Clock.System.now()
            }
        }

        val response = client.get("/admin/map-times") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, body.size)
        val entry = body[0].jsonObject
        assertEquals("kz_bhop", entry["map_name"]!!.jsonPrimitive.content)
        assertEquals(15_000L, entry["min_time_ms"]!!.jsonPrimitive.long)
    }

    // ─── PUT /admin/map-times/{mapName} ──────────────────────────────────────

    @Test
    fun `PUT map-times inserts a new minimum time`() = testApplication {
        setupAdminRoutes()

        val response = client.put("/admin/map-times/kz_canyon") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"min_time_ms":20000}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val minTime = transaction {
            MapMinimumTimesTable.selectAll()
                .where { MapMinimumTimesTable.mapName eq "kz_canyon" }
                .single()[MapMinimumTimesTable.minTimeMs]
        }
        assertEquals(20_000L, minTime)
    }

    @Test
    fun `PUT map-times updates an existing minimum time`() = testApplication {
        setupAdminRoutes()
        client.put("/admin/map-times/kz_canyon") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"min_time_ms":20000}""")
        }

        val response = client.put("/admin/map-times/kz_canyon") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"min_time_ms":30000}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val minTime = transaction {
            MapMinimumTimesTable.selectAll()
                .where { MapMinimumTimesTable.mapName eq "kz_canyon" }
                .single()[MapMinimumTimesTable.minTimeMs]
        }
        assertEquals(30_000L, minTime)
    }

    // ─── DELETE /admin/map-times/{mapName} ───────────────────────────────────

    @Test
    fun `DELETE map-times removes the entry`() = testApplication {
        setupAdminRoutes()
        transaction {
            MapsTable.insertIgnore { it[name] = "kz_del" }
            MapMinimumTimesTable.insert {
                it[mapName] = "kz_del"
                it[minTimeMs] = 10_000L
                it[updatedBy] = "admin"
                it[updatedAt] = Clock.System.now()
            }
        }

        val response = client.delete("/admin/map-times/kz_del") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val count = transaction {
            MapMinimumTimesTable.selectAll()
                .where { MapMinimumTimesTable.mapName eq "kz_del" }
                .count()
        }
        assertEquals(0L, count)
    }

    @Test
    fun `DELETE map-times on non-existent map still returns 204`() = testApplication {
        setupAdminRoutes()

        val response = client.delete("/admin/map-times/kz_nonexistent") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }
}
