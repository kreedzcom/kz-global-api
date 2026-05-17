package kz.global.api.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpAllowlistTest {

    @Test
    fun `isAllowed returns true when allowlist is null or blank`() {
        assertTrue(IpAllowlist.isAllowed("203.0.113.1", null))
        assertTrue(IpAllowlist.isAllowed("203.0.113.1", "  "))
    }

    @Test
    fun `isAllowed matches exact IP from comma-separated list`() {
        assertTrue(IpAllowlist.isAllowed("203.0.113.10", "203.0.113.10,203.0.113.11"))
        assertFalse(IpAllowlist.isAllowed("203.0.113.12", "203.0.113.10,203.0.113.11"))
    }

    @Test
    fun `isAllowed normalizes IPv4-mapped IPv6 addresses`() {
        assertTrue(IpAllowlist.isAllowed("::ffff:203.0.113.10", "203.0.113.10"))
    }

}
