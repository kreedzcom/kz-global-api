package kz.global.api.domain.replays

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kz.global.api.metrics.KzMetrics
import kz.global.api.support.testSecurityConfig
import kz.global.api.ws.ConnectedServersRegistry
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class ReplayServiceSecurityTest {

    @Test
    fun `servers with identical UIDs assemble independently`() {
        val service = ReplayService(
            r2Client = mockk(relaxed = true),
            metrics = KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
            security = testSecurityConfig(),
        )
        val first = service.parseChunk(ReplayServiceTest.buildChunk("shared-uid", zstdPayload, 0, 2))!!
        val last = service.parseChunk(ReplayServiceTest.buildChunk("shared-uid", ByteArray(2), 1, 2))!!

        val a = service.receive(first, serverId = 1)
        val b = service.receive(first, serverId = 2)
        val aComplete = service.receive(last, serverId = 1)
        val bComplete = service.receive(last, serverId = 2)

        assertSame(ReplayAssemblyResult.Pending, a)
        assertSame(ReplayAssemblyResult.Pending, b)
        assertIs<ReplayAssemblyResult.Complete>(aComplete)
        assertIs<ReplayAssemblyResult.Complete>(bComplete)
    }

    @Test
    fun `incomplete replay is rejected as soon as its bytes exceed the limit`() {
        val service = ReplayService(
            mockk(relaxed = true), KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
            testSecurityConfig(maxReplayBytes = 15),
        )
        val first = service.parseChunk(ReplayServiceTest.buildChunk("uid", zstdPayload, 0, 3))!!
        val second = service.parseChunk(ReplayServiceTest.buildChunk("uid", ByteArray(4), 1, 3))!!

        val initial = service.receive(first, 1)
        val oversized = service.receive(second, 1)
        val retry = service.receive(first, 1)

        assertSame(ReplayAssemblyResult.Pending, initial)
        assertSame(ReplayAssemblyResult.Rejected.TooLarge, oversized)
        assertSame(ReplayAssemblyResult.Pending, retry)
    }

    @Test
    fun `assembled bytes retain their budget until the consumer releases them`() {
        val service = ReplayService(
            mockk(relaxed = true), KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
            testSecurityConfig().copy(maxBufferedReplayBytes = 94),
        )
        val chunk = service.parseChunk(ReplayServiceTest.buildChunk("uid", zstdPayload, 0, 1))!!

        val inFlight = assertIs<ReplayAssemblyResult.Complete>(service.receive(chunk, 1))
        val blocked = service.receive(chunk, 2)
        inFlight.close()
        inFlight.close()
        val retried = service.receive(chunk, 2)

        assertSame(ReplayAssemblyResult.Rejected.TooLarge, blocked)
        assertIs<ReplayAssemblyResult.Complete>(retried).close()
    }

    @Test
    fun `duplicate chunks reuse capacity and old uploads release it`() {
        var now = Clock.System.now()
        val service = ReplayService(
            mockk(relaxed = true), KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
            testSecurityConfig().copy(maxBufferedReplayBytes = 90, replayUploadTtlMinutes = 1),
            clock = object : Clock { override fun now() = now },
        )
        val first = service.parseChunk(ReplayServiceTest.buildChunk("uid", zstdPayload, 0, 2))!!

        val initial = service.receive(first, 1)
        val repeated = service.receive(first, 1)
        val blocked = service.receive(first, 2)
        now += 2.minutes
        service.evictStaleUploads()
        val afterExpiry = service.receive(first, 2)

        assertSame(ReplayAssemblyResult.Pending, initial)
        assertSame(ReplayAssemblyResult.Pending, repeated)
        assertSame(ReplayAssemblyResult.Rejected.TooLarge, blocked)
        assertSame(ReplayAssemblyResult.Pending, afterExpiry)
    }

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
