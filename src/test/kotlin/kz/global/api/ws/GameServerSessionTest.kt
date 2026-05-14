package kz.global.api.ws

import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class GameServerSessionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun capturedSession(): Pair<GameServerSession, () -> List<WsEnvelope>> {
        val frames = mutableListOf<String>()
        val socket = mockk<DefaultWebSocketSession>(relaxed = true)
        coEvery { socket.send(any<Frame>()) } coAnswers {
            val f = firstArg<Frame>()
            if (f is Frame.Text) frames.add(f.readText())
        }
        val session = GameServerSession(42, socket)
        return session to { frames.map { json.decodeFromString(it) } }
    }

    // ─── Player tracking ─────────────────────────────────────────────────────

    @Test
    fun `players returns empty list on a new session`() = runTest {
        val (session, _) = capturedSession()

        assertTrue(session.players().isEmpty())
    }

    @Test
    fun `addPlayer makes player visible via players()`() = runTest {
        val (session, _) = capturedSession()
        val player = ConnectedPlayer("STEAM_0:0:1", "Alpha")

        session.addPlayer(player)

        assertEquals(listOf(player), session.players())
    }

    @Test
    fun `addPlayer with same steamid overwrites previous entry`() = runTest {
        val (session, _) = capturedSession()
        session.addPlayer(ConnectedPlayer("STEAM_0:0:1", "OldName"))

        session.addPlayer(ConnectedPlayer("STEAM_0:0:1", "NewName"))

        val players = session.players()
        assertEquals(1, players.size)
        assertEquals("NewName", players.single().nickname)
    }

    @Test
    fun `removePlayer removes only the specified player`() = runTest {
        val (session, _) = capturedSession()
        session.addPlayer(ConnectedPlayer("STEAM_0:0:1", "Alpha"))
        session.addPlayer(ConnectedPlayer("STEAM_0:0:2", "Beta"))

        session.removePlayer("STEAM_0:0:1")

        val players = session.players()
        assertEquals(1, players.size)
        assertEquals("STEAM_0:0:2", players.single().steamid)
    }

    @Test
    fun `removePlayer on non-existent steamid does nothing`() = runTest {
        val (session, _) = capturedSession()
        session.addPlayer(ConnectedPlayer("STEAM_0:0:1", "Alpha"))

        session.removePlayer("STEAM_0:0:999")

        assertEquals(1, session.players().size)
    }

    // ─── sendJson ────────────────────────────────────────────────────────────

    @Test
    fun `sendJson sends frame with correct msg_type`() = runTest {
        val (session, sent) = capturedSession()

        session.sendJson(MsgType.HELLO_ACK, 0L, HelloAckPayload())

        assertEquals(MsgType.HELLO_ACK, sent().single().msgType)
    }

    @Test
    fun `sendJson echoes the provided msgId`() = runTest {
        val (session, sent) = capturedSession()

        session.sendJson(MsgType.HELLO_ACK, 99L, HelloAckPayload())

        assertEquals(99L, sent().single().msgId)
    }

    @Test
    fun `sendJson encodes payload into the data field`() = runTest {
        val (session, sent) = capturedSession()

        session.sendJson(MsgType.MAP_INFO, 0L, MapInfoPayload(mapName = "kz_test"))

        val data = sent().single().data.jsonObject
        assertEquals("kz_test", data["map_name"]!!.jsonPrimitive.content)
    }

    // ─── sendError ───────────────────────────────────────────────────────────

    @Test
    fun `sendError sends frame with ERROR msg_type`() = runTest {
        val (session, sent) = capturedSession()

        session.sendError(0L, "something went wrong")

        assertEquals(MsgType.ERROR, sent().single().msgType)
    }

    @Test
    fun `sendError encodes the message into the data field`() = runTest {
        val (session, sent) = capturedSession()

        session.sendError(0L, "bad input")

        val data = sent().single().data.jsonObject
        assertEquals("bad input", data["message"]!!.jsonPrimitive.content)
    }

    // ─── currentMap ──────────────────────────────────────────────────────────

    @Test
    fun `currentMap starts empty and can be updated`() {
        val socket = mockk<DefaultWebSocketSession>(relaxed = true)
        val session = GameServerSession(1, socket)

        assertEquals("", session.currentMap)

        session.currentMap = "kz_canyon"

        assertEquals("kz_canyon", session.currentMap)
    }
}
