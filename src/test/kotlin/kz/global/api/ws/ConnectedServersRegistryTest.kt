package kz.global.api.ws

import io.mockk.mockk
import io.ktor.websocket.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ConnectedServersRegistryTest {

    private fun session(id: Int, map: String = ""): GameServerSession {
        val socket = mockk<DefaultWebSocketSession>(relaxed = true)
        return GameServerSession(id, socket).also { it.currentMap = map }
    }

    @Test
    fun `empty registry has zero connected count`() {
        val registry = ConnectedServersRegistry()

        assertEquals(0, registry.connectedCount())
    }

    @Test
    fun `register increments connected count`() {
        val registry = ConnectedServersRegistry()

        registry.register(session(1))
        registry.register(session(2))

        assertEquals(2, registry.connectedCount())
    }

    @Test
    fun `unregister decrements connected count`() {
        val registry = ConnectedServersRegistry()
        registry.register(session(1))
        registry.register(session(2))

        registry.unregister(1)

        assertEquals(1, registry.connectedCount())
    }

    @Test
    fun `registering same server id overwrites previous session`() {
        val registry = ConnectedServersRegistry()
        val first  = session(1, "kz_a")
        val second = session(1, "kz_b")
        registry.register(first)

        registry.register(second)

        assertEquals(1, registry.connectedCount())
        assertSame(second, registry.get(1))
    }

    @Test
    fun `get returns registered session`() {
        val registry = ConnectedServersRegistry()
        val s = session(42)
        registry.register(s)

        val result = registry.get(42)

        assertSame(s, result)
    }

    @Test
    fun `get returns null for unknown server`() {
        val registry = ConnectedServersRegistry()

        assertNull(registry.get(999))
    }

    @Test
    fun `sessionsOnMap returns only sessions on that map`() {
        val registry = ConnectedServersRegistry()
        registry.register(session(1, "kz_canyon"))
        registry.register(session(2, "kz_bhop"))
        registry.register(session(3, "kz_canyon"))

        val result = registry.sessionsOnMap("kz_canyon")

        assertEquals(2, result.size)
        assertTrue(result.all { it.currentMap == "kz_canyon" })
        assertTrue(result.map { it.serverId }.containsAll(listOf(1, 3)))
    }

    @Test
    fun `sessionsOnMap returns empty list when no sessions on that map`() {
        val registry = ConnectedServersRegistry()
        registry.register(session(1, "kz_a"))

        val result = registry.sessionsOnMap("kz_unknown")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `allSessions returns all registered sessions`() {
        val registry = ConnectedServersRegistry()
        registry.register(session(1))
        registry.register(session(2))
        registry.register(session(3))

        val result = registry.allSessions()

        assertEquals(3, result.size)
    }

    @Test
    fun `disconnect removes session from registry`() = runTest {
        val registry = ConnectedServersRegistry()
        registry.register(session(7))

        registry.disconnect(7)

        assertNull(registry.get(7))
        assertEquals(0, registry.connectedCount())
    }

    @Test
    fun `closeAll clears all sessions`() = runTest {
        val registry = ConnectedServersRegistry()
        registry.register(session(1))
        registry.register(session(2))

        registry.closeAll()

        assertEquals(0, registry.connectedCount())
    }
}
