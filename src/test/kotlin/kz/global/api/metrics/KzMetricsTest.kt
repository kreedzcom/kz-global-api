package kz.global.api.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.ktor.websocket.DefaultWebSocketSession
import kz.global.api.ws.ConnectedPlayer
import kz.global.api.ws.ConnectedServersRegistry
import kz.global.api.ws.GameServerSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KzMetricsTest {

    private val registry = ConnectedServersRegistry()
    private val meterRegistry = SimpleMeterRegistry()
    private val metrics = KzMetrics(meterRegistry, registry)

    @Test
    fun `recordPlayerJoin increments counter with server and banned tags`() {
        metrics.recordPlayerJoin(42, banned = false)
        metrics.recordPlayerJoin(42, banned = true)

        assertEquals(
            1.0,
            meterRegistry.counter(
                "kz_player_joins_total",
                "server_id",
                "42",
                "banned",
                "false",
            ).count(),
        )
        assertEquals(
            1.0,
            meterRegistry.counter(
                "kz_player_joins_total",
                "server_id",
                "42",
                "banned",
                "true",
            ).count(),
        )
    }

    @Test
    fun `recordPlayerLeave increments counter with server tag`() {
        metrics.recordPlayerLeave(7)

        assertEquals(
            1.0,
            meterRegistry.counter("kz_player_leaves_total", "server_id", "7").count(),
        )
    }

    @Test
    fun `recordRejected increments counter with reason tag`() {
        metrics.recordRejected(RecordRejectReason.BANNED)

        assertEquals(
            1.0,
            meterRegistry.counter(
                "kz_records_rejected_total",
                "reason",
                RecordRejectReason.BANNED,
            ).count(),
        )
    }

    @Test
    fun `connected players gauge reflects session player counts`() = runTest {
        val socket = mockk<DefaultWebSocketSession>(relaxed = true)
        val session = GameServerSession(1, socket)
        registry.register(session)
        session.addPlayer(ConnectedPlayer("STEAM_0:0:1", "Alpha"))
        session.addPlayer(ConnectedPlayer("STEAM_0:0:2", "Beta"))

        assertEquals(2.0, meterRegistry.get("kz_connected_players").gauge().value())
    }
}
