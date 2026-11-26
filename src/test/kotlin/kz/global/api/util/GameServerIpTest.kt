package kz.global.api.util

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class GameServerIpTest {

    private val trustedProxies = setOf(
        InetAddress.ofLiteral("192.0.2.1"),
        InetAddress.ofLiteral("2001:db8::1"),
    )

    @Test
    fun `untrusted peer supplies its socket address`() {
        val forwarded = listOf("198.51.100.2")

        val address = resolveGameServerIp("198.51.100.3", forwarded, trustedProxies)

        assertEquals("198.51.100.3", address)
    }

    @Test
    fun `closest untrusted hop supplies the game server address`() {
        val forwarded = listOf("198.51.100.2, 2001:db8::2", "192.0.2.1")

        val address = resolveGameServerIp("2001:db8::1", forwarded, trustedProxies)

        assertEquals(InetAddress.ofLiteral("2001:db8::2").hostAddress, address)
    }

    @Test
    fun `invalid or missing address falls back to the trusted socket peer`() {
        val invalidChains = listOf(emptyList(), listOf("198.51.100.2, invalid"), listOf("2001:db8::1"))

        val addresses = invalidChains.map { resolveGameServerIp("192.0.2.1", it, trustedProxies) }

        assertEquals(List(3) { "192.0.2.1" }, addresses)
    }
}
