package kz.global.api.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

object GameServersTable : Table("game_server") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255).uniqueIndex()
    val accessKey = binary("access_key", 16).uniqueIndex()
    val active = bool("active").default(true)
    val lastConnectedAt = timestamp("last_connected_at").nullable()
    val allowedIps = varchar("allowed_ips", 1024).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)
}
