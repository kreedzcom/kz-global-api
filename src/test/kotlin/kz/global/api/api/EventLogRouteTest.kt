package kz.global.api.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kz.global.api.db.tables.EventLogTable
import kz.global.api.db.tables.GameServersTable
import kz.global.api.support.TestDatabase
import kz.global.api.support.adminAuth
import kz.global.api.support.setupAdminRoutes
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventLogRouteTest {

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
        transaction {
            val insertedServerId = GameServersTable.insert {
                it[name] = "TestServer"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]

            EventLogTable.insert {
                it[eventType] = "RECORD_DELETED"
                it[EventLogTable.serverId] = insertedServerId
                it[payload] = """{"record_id":"abc"}"""
            }
            EventLogTable.insert {
                it[eventType] = "PLAYER_BANNED"
                it[EventLogTable.serverId] = null
                it[payload] = """{"steamid":"STEAM_0:0:1"}"""
            }
        }
    }

    @Test
    fun `GET event-log requires admin auth`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/event-log")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET event-log returns paginated entries newest first`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/event-log") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("RECORD_DELETED"))
        assertTrue(body.contains("PLAYER_BANNED"))
        assertTrue(body.contains("\"total_count\":2"))
    }

    @Test
    fun `GET event-log filters by event type`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/event-log?event_type=PLAYER_BANNED") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("PLAYER_BANNED"))
        assertFalse(body.contains("RECORD_DELETED"))
    }

    @Test
    fun `GET event-log filters by server id`() = testApplication {
        setupAdminRoutes()

        val serverId = transaction {
            GameServersTable.selectAll().single()[GameServersTable.id]
        }

        val response = client.get("/admin/event-log?server_id=$serverId") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("RECORD_DELETED"))
        assertFalse(body.contains("PLAYER_BANNED"))
    }

}
