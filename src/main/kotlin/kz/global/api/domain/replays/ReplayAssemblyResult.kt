package kz.global.api.domain.replays

import java.util.concurrent.atomic.AtomicBoolean

sealed class ReplayAssemblyResult {
    data object Pending : ReplayAssemblyResult()

    class Complete(val bytes: ByteArray, private val release: () -> Unit = {}) : ReplayAssemblyResult(), AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) release()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Complete) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    sealed class Rejected : ReplayAssemblyResult() {
        data object CrcMismatch : Rejected()

        data object BadZstdMagic : Rejected()

        data object InvalidChunk : Rejected()

        data object TooLarge : Rejected()

        data object TooManyConcurrent : Rejected()
    }
}
