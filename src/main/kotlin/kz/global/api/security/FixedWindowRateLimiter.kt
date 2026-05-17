package kz.global.api.security

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * In-memory fixed-window rate limiter keyed by arbitrary string (server id, client IP, etc.).
 */
class FixedWindowRateLimiter(
    private val window: Duration,
    private val maxRequests: Int,
) {

    private data class WindowState(var windowStart: Instant, var count: Int)

    private val windows = ConcurrentHashMap<String, WindowState>()

    fun tryAcquire(key: String): Boolean {
        val now = Clock.System.now()
        val state = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.windowStart >= window) {
                WindowState(now, 1)
            } else {
                existing.count++
                existing
            }
        }!!
        return state.count <= maxRequests
    }

    fun reset(key: String) {
        windows.remove(key)
    }

}

/**
 * Sliding budget for byte throughput (e.g. replay chunks per server).
 */
class ByteBudgetRateLimiter(
    private val window: Duration,
    private val maxBytes: Long,
) {

    private data class Budget(var windowStart: Instant, var bytes: Long)

    private val budgets = ConcurrentHashMap<String, Budget>()

    fun tryAcquire(key: String, bytes: Int): Boolean {
        if (bytes <= 0) return true
        val now = Clock.System.now()
        val budget = budgets.compute(key) { _, existing ->
            if (existing == null || now - existing.windowStart >= window) {
                Budget(now, bytes.toLong())
            } else {
                existing.bytes += bytes
                existing
            }
        }!!
        return budget.bytes <= maxBytes
    }

}
