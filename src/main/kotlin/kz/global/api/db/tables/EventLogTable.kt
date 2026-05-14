package kz.global.api.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

object EventLogTable : Table("event_log") {

    val id = long("id").autoIncrement()
    val serverId = integer("server_id").nullable()
    val eventType = varchar("event_type", 64)
    val payload = text("payload")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, eventType)
        index(false, createdAt)
    }

}
