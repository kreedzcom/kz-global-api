package kz.global.api.domain.replays

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kz.global.api.metrics.KzMetrics
import kz.global.api.support.testSecurityConfig
import kz.global.api.ws.ConnectedServersRegistry
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class ReplayServiceSecurityTest {

    private val zstdPayload = ReplayServiceTest.ZSTD_MAGIC + ByteArray(8) { it.toByte() }

    @Test
    fun `receive returns TooLarge when assembled replay exceeds max bytes`() {
        val service = ReplayService(
            r2Client = mockk(relaxed = true),
            metrics = KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
            security = testSecurityConfig(maxReplayBytes = 20),
        )
        val chunk = service.parseChunk(
            ReplayServiceTest.buildChunk("big-uid", zstdPayload + ByteArray(30), 0, 1),
        )!!

        val result = service.receive(chunk, serverId = 1)

        assertIs<ReplayAssemblyResult.Rejected.TooLarge>(result)
    }

    @Test
    fun `receive returns TooManyConcurrent when server exceeds upload slots`() {
        val service = ReplayService(
            r2Client = mockk(relaxed = true),
            metrics = KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
            security = testSecurityConfig(maxConcurrentReplayUploadsPerServer = 1),
        )
        val pending = service.parseChunk(
            ReplayServiceTest.buildChunk("uid-a", zstdPayload, 0, 2),
        )!!
        assertSame(ReplayAssemblyResult.Pending, service.receive(pending, serverId = 1))

        val second = service.parseChunk(
            ReplayServiceTest.buildChunk("uid-b", zstdPayload, 0, 2),
        )!!
        val result = service.receive(second, serverId = 1)

        assertIs<ReplayAssemblyResult.Rejected.TooManyConcurrent>(result)
    }

}
