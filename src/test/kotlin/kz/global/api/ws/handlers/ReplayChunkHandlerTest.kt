package kz.global.api.ws.handlers

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kz.global.api.domain.replays.ReplayService
import kz.global.api.metrics.KzMetrics
import kz.global.api.support.mockSession
import kz.global.api.ws.ConnectedServersRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ReplayChunkHandlerTest {

    private val metrics = KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry())

    @Test
    fun `handle ignores malformed replay chunk shorter than header`() = runTest {
        val replayService = ReplayService(mockk(relaxed = true), metrics)
        val handler = ReplayChunkHandler(replayService, metrics)
        val (session, sent) = mockSession()

        handler.handle(session, ByteArray(91))

        assertTrue(sent().isEmpty())
    }
}
