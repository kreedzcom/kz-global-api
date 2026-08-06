package kz.global.api.security

import io.ktor.server.application.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object AdminActor {

    const val HEADER = "X-Admin-Actor"
    const val MAX_LENGTH = 64

    fun fromHeader(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val sanitized = raw.trim()
            .take(MAX_LENGTH)
            .filter { !it.isISOControl() }
        return sanitized.takeIf { it.isNotEmpty() }
    }

    fun fromCall(call: ApplicationCall): String? =
        fromHeader(call.request.headers[HEADER])

    fun withActor(base: JsonObject, actor: String?): JsonObject {
        if (actor == null) return base
        return buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("actor", JsonPrimitive(actor))
        }
    }

}
