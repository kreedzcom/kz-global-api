package kz.global.api.ws.handlers

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kz.global.api.metrics.KzMetrics
import kz.global.api.support.mockSession
import kz.global.api.ws.*
import kz.global.api.ws.ConnectedServersRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.BeforeEach
import kotlin.test.*

class PlayerLeaveHandlerTest {

    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var metrics: KzMetrics
    private lateinit var handler: PlayerLeaveHandler

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        metrics = KzMetrics(meterRegistry, ConnectedServersRegistry())
        handler = PlayerLeaveHandler(metrics)
    }

    private fun envelope(payload: PlayerLeavePayload) = WsEnvelope(
        msgType = MsgType.PLAYER_LEAVE,
        data = Json.encodeToJsonElement(payload),
    )

    @Test
    fun `handle removes the player from the session`() = runTest {
        val (session, _) = mockSession()
        session.addPlayer(ConnectedPlayer("STEAM_0:0:1", "Alpha"))
        session.addPlayer(ConnectedPlayer("STEAM_0:0:2", "Beta"))

        handler.handle(session, envelope(PlayerLeavePayload("STEAM_0:0:1")))

        val remaining = session.players()
        assertEquals(1, remaining.size)
        assertEquals("STEAM_0:0:2", remaining.single().steamid)
    }

    @Test
    fun `handle for non-tracked player does not throw`() = runTest {
        val (session, _) = mockSession()

        handler.handle(session, envelope(PlayerLeavePayload("STEAM_0:0:999")))

        assertTrue(session.players().isEmpty())
    }

    @Test
    fun `handle does not send any response frame`() = runTest {
        val (session, sent) = mockSession()
        session.addPlayer(ConnectedPlayer("STEAM_0:0:1", "Alpha"))

        handler.handle(session, envelope(PlayerLeavePayload("STEAM_0:0:1")))

        assertTrue(sent().isEmpty())
    }

    @Test
    fun `handle increments player leave metric`() = runTest {
        val (session, _) = mockSession()
        session.addPlayer(ConnectedPlayer("STEAM_0:0:1", "Alpha"))

        handler.handle(session, envelope(PlayerLeavePayload("STEAM_0:0:1")))

        assertEquals(
            1.0,
            meterRegistry.counter(
                "kz_player_leaves_total",
                "server_id",
                session.serverId.toString(),
            ).count(),
        )
    }
}
