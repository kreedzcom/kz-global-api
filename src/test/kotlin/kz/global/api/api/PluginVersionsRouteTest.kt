package kz.global.api.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kz.global.api.db.tables.PluginVersionsTable
import kz.global.api.support.TestDatabase
import kz.global.api.support.adminAuth
import kz.global.api.support.setupAdminRoutes
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PluginVersionsRouteTest {

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
    }

    // ─── GET /admin/plugin-versions ──────────────────────────────────────────

    @Test
    fun `GET plugin-versions requires admin auth`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/plugin-versions")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET plugin-versions returns empty list when none published`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/plugin-versions") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `GET plugin-versions returns published versions`() = testApplication {
        setupAdminRoutes()
        transaction {
            PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }
        }

        val response = client.get("/admin/plugin-versions") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, body.size)
        assertEquals("1.0.0", body[0].jsonObject["semver"]!!.jsonPrimitive.content)
    }

    // ─── POST /admin/plugin-versions ─────────────────────────────────────────

    @Test
    fun `POST plugin-versions creates a new version`() = testApplication {
        setupAdminRoutes()
        val linuxChecksum = "a".repeat(32)
        val winChecksum   = "b".repeat(32)

        val response = client.post("/admin/plugin-versions") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"semver":"2.0.0","checksum_linux":"$linuxChecksum","checksum_windows":"$winChecksum"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("2.0.0", body["semver"]!!.jsonPrimitive.content)
        assertNotNull(body["id"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `POST plugin-versions rejects invalid checksum length`() = testApplication {
        setupAdminRoutes()

        val response = client.post("/admin/plugin-versions") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"semver":"1.0.0","checksum_linux":"tooshort","checksum_windows":"${"a".repeat(32)}"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─── PATCH /admin/plugin-versions/{id}/cutoff ────────────────────────────

    @Test
    fun `PATCH cutoff marks version as cutoff`() = testApplication {
        setupAdminRoutes()
        val id = transaction {
            PluginVersionsTable.insert {
                it[semver] = "0.9.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
        }

        val response = client.patch("/admin/plugin-versions/$id/cutoff") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val isCutoff = transaction {
            PluginVersionsTable.selectAll()
                .where { PluginVersionsTable.id eq id }
                .single()[PluginVersionsTable.isCutoff]
        }
        assertTrue(isCutoff)
    }
}
