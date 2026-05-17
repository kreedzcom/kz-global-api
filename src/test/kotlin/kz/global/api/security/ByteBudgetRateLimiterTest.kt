package kz.global.api.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ByteBudgetRateLimiterTest {

    @Test
    fun `tryAcquire allows bytes up to max within window`() {
        val limiter = ByteBudgetRateLimiter(1.seconds, maxBytes = 100)

        assertTrue(limiter.tryAcquire("server-1", 60))
        assertTrue(limiter.tryAcquire("server-1", 40))
        assertFalse(limiter.tryAcquire("server-1", 1))
    }

    @Test
    fun `tryAcquire ignores non-positive byte counts`() {
        val limiter = ByteBudgetRateLimiter(1.seconds, maxBytes = 10)

        assertTrue(limiter.tryAcquire("server-1", 0))
        assertTrue(limiter.tryAcquire("server-1", -5))
        assertTrue(limiter.tryAcquire("server-1", 10))
    }

    @Test
    fun `different keys have independent byte budgets`() {
        val limiter = ByteBudgetRateLimiter(1.seconds, maxBytes = 50)

        assertTrue(limiter.tryAcquire("a", 50))
        assertFalse(limiter.tryAcquire("a", 1))
        assertTrue(limiter.tryAcquire("b", 50))
    }

}
