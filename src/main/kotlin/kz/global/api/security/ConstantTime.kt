package kz.global.api.security

import java.security.MessageDigest

fun constantTimeEquals(provided: String?, expected: String): Boolean {
    if (provided == null) return false
    val a = provided.toByteArray(Charsets.UTF_8)
    val b = expected.toByteArray(Charsets.UTF_8)
    if (a.size != b.size) return false
    return MessageDigest.isEqual(a, b)
}
