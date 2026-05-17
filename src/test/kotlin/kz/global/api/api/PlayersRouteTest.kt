package kz.global.api.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kz.global.api.db.tables.PlayersTable
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
        }
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

}
