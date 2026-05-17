package kz.global.api.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kz.global.api.db.tables.GameServersTable
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
class ServersRouteTest {

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
    }

    // ─── Authentication ───────────────────────────────────────────────────────

    @Test
    fun `GET servers requires admin auth`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/servers")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET servers rejects wrong bearer key`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/servers") {
            header(HttpHeaders.Authorization, "Bearer wrong-key")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ─── GET /admin/servers ───────────────────────────────────────────────────

    @Test
    fun `GET servers returns empty list when no servers exist`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/servers") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `GET servers returns existing servers`() = testApplication {
        setupAdminRoutes()
        transaction {
            GameServersTable.insert {
                it[name] = "server-a"
                it[accessKey] = ByteArray(16)
            }
            GameServersTable.insert {
                it[name] = "server-b"
                it[accessKey] = ByteArray(16) { 1 }
            }
        }

        val response = client.get("/admin/servers") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(2, body.size)
        assertEquals(setOf("server-a", "server-b"), body.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
    }

    // ─── POST /admin/servers ──────────────────────────────────────────────────

    @Test
    fun `POST servers creates a server and returns 32-char access key`() = testApplication {
        setupAdminRoutes()

        val response = client.post("/admin/servers") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"name":"new-server"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("new-server", body["name"]!!.jsonPrimitive.content)
        assertEquals(32, body["accessKey"]!!.jsonPrimitive.content.length, "Access key should be 16 bytes = 32 hex chars")
    }

    @Test
    fun `POST servers persists the server in the database`() = testApplication {
        setupAdminRoutes()

        client.post("/admin/servers") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"name":"persist-check"}""")
        }

        val count = transaction {
            GameServersTable.selectAll()
                .where { GameServersTable.name eq "persist-check" }
                .count()
        }
        assertEquals(1L, count)
    }

    // ─── PATCH /admin/servers/{id} ────────────────────────────────────────────

    @Test
    fun `PATCH server updates allowed_ips`() = testApplication {
        setupAdminRoutes()
        val serverId = transaction {
            GameServersTable.insert {
                it[name] = "ip-patch"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
        }

        val response = client.patch("/admin/servers/$serverId") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"allowed_ips":"203.0.113.10,203.0.113.11"}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)

        val allowedIps = transaction {
            GameServersTable.selectAll()
                .where { GameServersTable.id eq serverId }
                .single()[GameServersTable.allowedIps]
        }
        assertEquals("203.0.113.10,203.0.113.11", allowedIps)
    }

    @Test
    fun `PATCH server with non-numeric id returns 400`() = testApplication {
        setupAdminRoutes()

        val response = client.patch("/admin/servers/not-an-id") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"allowed_ips":"203.0.113.10"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PATCH server without allowed_ips returns 400`() = testApplication {
        setupAdminRoutes()
        val serverId = transaction {
            GameServersTable.insert {
                it[name] = "ip-patch-missing"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
        }

        val response = client.patch("/admin/servers/$serverId") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PATCH server requires admin auth`() = testApplication {
        setupAdminRoutes()
        val serverId = transaction {
            GameServersTable.insert {
                it[name] = "ip-patch-auth"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
        }

        val response = client.patch("/admin/servers/$serverId") {
            contentType(ContentType.Application.Json)
            setBody("""{"allowed_ips":"203.0.113.10"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ─── DELETE /admin/servers/{id} ───────────────────────────────────────────

    @Test
    fun `DELETE server sets active to false`() = testApplication {
        setupAdminRoutes()
        val serverId = transaction {
            GameServersTable.insert {
                it[name] = "to-delete"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
        }

        val response = client.delete("/admin/servers/$serverId") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val active = transaction {
            GameServersTable.selectAll()
                .where { GameServersTable.id eq serverId }
                .single()[GameServersTable.active]
        }
        assertFalse(active)
    }

    @Test
    fun `DELETE server with non-numeric id returns 400`() = testApplication {
        setupAdminRoutes()

        val response = client.delete("/admin/servers/abc") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─── GET /admin/servers/connected ─────────────────────────────────────────

    @Test
    fun `GET connected returns empty list when no sessions active`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/servers/connected") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
    }
}
