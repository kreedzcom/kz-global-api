package kz.global.api.domain.replays

sealed class ReplayAssemblyResult {
    data object Pending : ReplayAssemblyResult()

    class Complete(val bytes: ByteArray) : ReplayAssemblyResult() {
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
    }
}
