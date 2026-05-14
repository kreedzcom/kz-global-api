package kz.global.api.events

import kz.global.api.util.uuidV7
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class KzEventBusTest {

    @Test
    fun `emitted NewRecord is received by a subscriber`() = runTest {
        val bus = KzEventBus()
        val received = mutableListOf<KzEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        yield() // let the collector register before emitting

        val id = uuidV7()
        bus.emit(KzEvent.NewRecord(id, "STEAM_0:0:1", "kz_canyon", 30_000L, 0))
        advanceUntilIdle()
        job.cancel()

        assertEquals(1, received.size)
        val event = received[0] as KzEvent.NewRecord
        assertEquals(id, event.recordId)
        assertEquals("STEAM_0:0:1", event.playerSteamid)
        assertEquals("kz_canyon", event.mapName)
        assertEquals(30_000L, event.timeMs)
        assertEquals(0, event.teleports)
    }

    @Test
    fun `emitted NewWorldRecord is received by a subscriber`() = runTest {
        val bus = KzEventBus()
        val received = mutableListOf<KzEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        yield()

        val id = uuidV7()
        bus.emit(KzEvent.NewWorldRecord(id, "STEAM_0:0:2", "kz_bhop", 15_000L, "pro"))
        advanceUntilIdle()
        job.cancel()

        assertEquals(1, received.size)
        val event = received[0] as KzEvent.NewWorldRecord
        assertEquals("pro", event.category)
        assertEquals("kz_bhop", event.mapName)
    }

    @Test
    fun `multiple subscribers all receive the same event`() = runTest {
        val bus = KzEventBus()
        val count1 = AtomicInteger(0)
        val count2 = AtomicInteger(0)
        val job1 = launch { bus.events.collect { count1.incrementAndGet() } }
        val job2 = launch { bus.events.collect { count2.incrementAndGet() } }
        yield() // let both collectors register

        repeat(5) {
            bus.emit(KzEvent.NewRecord(uuidV7(), "STEAM_0:0:1", "kz_map", 1_000L * it, 0))
        }
        advanceUntilIdle()
        job1.cancel()
        job2.cancel()

        assertEquals(5, count1.get())
        assertEquals(5, count2.get())
    }

    @Test
    fun `subscriber added after events are emitted misses earlier events`() = runTest {
        val bus = KzEventBus()
        bus.emit(KzEvent.NewRecord(uuidV7(), "STEAM_0:0:1", "kz_map", 1000L, 0))

        // Subscribe AFTER the already-emitted event (no yield) — SharedFlow has no replay
        val received = mutableListOf<KzEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(0, received.size)
    }

    @Test
    fun `multiple events are received in emission order`() = runTest {
        val bus = KzEventBus()
        val received = mutableListOf<KzEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        yield()

        val timings = listOf(1000L, 2000L, 3000L)
        timings.forEach { t ->
            bus.emit(KzEvent.NewRecord(uuidV7(), "STEAM_0:0:1", "kz_map", t, 0))
        }
        advanceUntilIdle()
        job.cancel()

        assertEquals(3, received.size)
        assertEquals(timings, received.map { (it as KzEvent.NewRecord).timeMs })
    }
}
