package kz.global.api.ws.handlers

import kz.global.api.db.tables.PlayersTable
import kz.global.api.domain.players.PlayerBanService
import kz.global.api.support.TestDatabase
import kz.global.api.support.mockSession
import kz.global.api.ws.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayerJoinHandlerTest {

    private val handler = PlayerJoinHandler(PlayerBanService())

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
    }

    private fun envelope(payload: PlayerJoinPayload, msgId: Long = 0L) = WsEnvelope(
        msgType = MsgType.PLAYER_JOIN,
        msgId = msgId,
        data = Json.encodeToJsonElement(payload),
    )

    @Test
    fun `handle upserts player into the database`() = runTest {
        val (session, _) = mockSession()

        handler.handle(session, envelope(PlayerJoinPayload("STEAM_0:0:1", "SpeedRunner", "1.2.3.4")))

        val player = transaction {
            PlayersTable.selectAll()
                .where { PlayersTable.steamid eq "STEAM_0:0:1" }
                .singleOrNull()
        }
        assertNotNull(player)
        assertEquals("SpeedRunner", player[PlayersTable.lastNickname])
    }

    @Test
    fun `handle updates nickname on second join`() = runTest {
        val (session, _) = mockSession()
        handler.handle(session, envelope(PlayerJoinPayload("STEAM_0:0:2", "OldName")))

        handler.handle(session, envelope(PlayerJoinPayload("STEAM_0:0:2", "NewName")))

        val player = transaction {
            PlayersTable.selectAll().where { PlayersTable.steamid eq "STEAM_0:0:2" }.single()
        }
        assertEquals("NewName", player[PlayersTable.lastNickname])
    }

    @Test
    fun `handle adds player to session`() = runTest {
        val (session, _) = mockSession()

        handler.handle(session, envelope(PlayerJoinPayload("STEAM_0:0:3", "Tester")))

        val players = session.players()
        assertEquals(1, players.size)
        assertEquals("STEAM_0:0:3", players.single().steamid)
    }

    @Test
    fun `handle sends PLAYER_JOIN ack with is_banned false`() = runTest {
        val (session, sent) = mockSession()

        handler.handle(session, envelope(PlayerJoinPayload("STEAM_0:0:4", "Tester"), msgId = 5L))

        val frames = sent()
        assertEquals(1, frames.size)
        val frame = frames.single()
        assertEquals(MsgType.PLAYER_JOIN, frame.msgType)
        assertEquals(5L, frame.msgId)
        assertEquals("STEAM_0:0:4", frame.data.jsonObject["steamid"]!!.jsonPrimitive.content)
        assertFalse(frame.data.jsonObject["is_banned"]!!.jsonPrimitive.boolean)
    }
}
