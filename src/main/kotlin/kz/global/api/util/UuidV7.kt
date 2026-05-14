package kz.global.api.util

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

private val lastMs = AtomicLong(0L)
private val seq = AtomicLong(0L)

/**
 * Generates a UUID version 7 (time-ordered, random).
 * Structure: [48-bit ms][4-bit version=7][12-bit seq][2-bit variant=10][62-bit random]
 */
fun uuidV7(): Uuid {
    val ms = System.currentTimeMillis()
    val seqVal = if (ms > lastMs.getAndSet(ms)) {
        seq.set(0L)
        0L
    } else {
        seq.incrementAndGet() and 0x0FFFL
    }

    val msb = (ms shl 16) or 0x7000L or seqVal
    val lsb = (ThreadLocalRandom.current().nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
    return Uuid.fromLongs(msb, lsb)
}
