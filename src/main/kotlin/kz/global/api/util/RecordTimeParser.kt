package kz.global.api.util

object RecordTimeParser {

    /** Parses KZ-style time strings (e.g. `83.45`, `1:23.45`, raw ms) to milliseconds. */
    fun parseToMs(input: String): Long? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.all { it.isDigit() }) {
            return trimmed.toLongOrNull()
        }

        if (':' in trimmed) {
            val parts = trimmed.split(':', limit = 2)
            if (parts.size != 2) return null
            val minutes = parts[0].toLongOrNull() ?: return null
            val seconds = parts[1].toDoubleOrNull() ?: return null
            return minutes * 60_000L + (seconds * 1_000).toLong()
        }

        val seconds = trimmed.toDoubleOrNull() ?: return null
        return (seconds * 1_000).toLong()
    }
}
