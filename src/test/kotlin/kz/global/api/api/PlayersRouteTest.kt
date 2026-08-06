package kz.global.api.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kz.global.api.db.tables.EventLogTable
import kz.global.api.db.tables.PlayersTable
import kz.global.api.security.AdminActor
import kz.global.api.support.TestDatabase
import kz.global.api.support.adminAuth
import kz.global.api.support.setupAdminRoutes
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayersRouteTest {

    private val steamid = "STEAM_0:0:42424"

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
        transaction {
            val sid = steamid
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = sid
                it[lastNickname] = "Target"
            }
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = "STEAM_0:0:99999"
                it[lastNickname] = "OtherPlayer"
            }
        }
    }

    @Test
    fun `GET players requires admin auth`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/players")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET players returns paginated list`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/players") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Target"))
        assertTrue(body.contains("OtherPlayer"))
        assertTrue(body.contains("\"total_count\":2"))
    }

    @Test
    fun `GET players filters by search`() = testApplication {
        setupAdminRoutes()

        val response = client.get("/admin/players?search=Target") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Target"))
        assertFalse(body.contains("OtherPlayer"))
    }

    @Test
    fun `GET players filters by banned`() = testApplication {
        setupAdminRoutes()
        transaction {
            PlayersTable.update({ PlayersTable.steamid eq steamid }) {
                it[isBanned] = true
            }
        }

        val response = client.get("/admin/players?banned=true") {
            header(HttpHeaders.Authorization, adminAuth())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Target"))
        assertFalse(body.contains("OtherPlayer"))
    }

    @Test
    fun `PATCH ban sets is_banned true`() = testApplication {
        setupAdminRoutes()

        val response = client.patch("/admin/players/$steamid/ban") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"is_banned":true}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)

        transaction {
            val banned = PlayersTable.selectAll().where { PlayersTable.steamid eq steamid }.single()[PlayersTable.isBanned]
            assertTrue(banned)
        }
    }

    @Test
    fun `PATCH ban requires admin auth`() = testApplication {
        setupAdminRoutes()

        val response = client.patch("/admin/players/$steamid/ban") {
            contentType(ContentType.Application.Json)
            setBody("""{"is_banned":true}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PATCH ban can unban player`() = testApplication {
        setupAdminRoutes()
        transaction {
            PlayersTable.update({ PlayersTable.steamid eq steamid }) {
                it[isBanned] = true
            }
        }

        val response = client.patch("/admin/players/$steamid/ban") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"is_banned":false}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)

        transaction {
            val banned = PlayersTable.selectAll().where { PlayersTable.steamid eq steamid }.single()[PlayersTable.isBanned]
            assertFalse(banned)
        }
    }

    @Test
    fun `PATCH ban returns 404 for unknown steamid`() = testApplication {
        setupAdminRoutes()

        val response = client.patch("/admin/players/STEAM_0:0:00000/ban") {
            header(HttpHeaders.Authorization, adminAuth())
            contentType(ContentType.Application.Json)
            setBody("""{"is_banned":true}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PATCH ban writes audit log with actor`() = testApplication {
        setupAdminRoutes()

        val response = client.patch("/admin/players/$steamid/ban") {
            header(HttpHeaders.Authorization, adminAuth())
            header(AdminActor.HEADER, "TestAdmin")
            contentType(ContentType.Application.Json)
            setBody("""{"is_banned":true}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)

        transaction {
            val event = EventLogTable.selectAll()
                .where { EventLogTable.eventType eq "PLAYER_BANNED" }
                .single()
            assertTrue(event[EventLogTable.payload].contains("TestAdmin"))
            assertTrue(event[EventLogTable.payload].contains(steamid))
        }
    }

}
