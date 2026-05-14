package kz.global.api.events

import kz.global.api.db.tables.EventLogTable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

class AuditLogger {

    private val log = LoggerFactory.getLogger(AuditLogger::class.java)

    suspend fun log(eventType: String, serverId: Int? = null, payload: JsonObject) {
        runCatching {
            suspendTransaction {
                EventLogTable.insert {
                    it[EventLogTable.eventType] = eventType
                    it[EventLogTable.serverId] = serverId
                    it[EventLogTable.payload] = Json.encodeToString(payload)
                }
            }
        }.onFailure { e ->
            log.error("Failed to write audit log [{}]: {}", eventType, e.message)
        }
    }

    suspend fun log(eventType: String, serverId: Int? = null, vararg pairs: Pair<String, String>) {
        val payload = buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
        log(eventType, serverId, payload)
    }

}
