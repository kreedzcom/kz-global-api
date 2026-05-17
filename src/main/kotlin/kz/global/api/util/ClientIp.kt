package kz.global.api.util

import io.ktor.server.application.*
import io.ktor.server.request.*

fun ApplicationCall.clientIp(): String {
    val forwarded = request.headers["X-Forwarded-For"]
        ?.split(',')
        ?.firstOrNull()
        ?.trim()
    if (!forwarded.isNullOrEmpty()) return forwarded
    return request.local.remoteHost
}
