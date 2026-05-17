package kz.global.api.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class FixedWindowRateLimiterTest {

    @Test
    fun `tryAcquire allows up to max requests per window`() {
        val limiter = FixedWindowRateLimiter(1.minutes, 3)

        assertTrue(limiter.tryAcquire("key"))
        assertTrue(limiter.tryAcquire("key"))
        assertTrue(limiter.tryAcquire("key"))
        assertFalse(limiter.tryAcquire("key"))
    }

    @Test
    fun `different keys have independent limits`() {
        val limiter = FixedWindowRateLimiter(1.minutes, 1)

        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
    }

}
