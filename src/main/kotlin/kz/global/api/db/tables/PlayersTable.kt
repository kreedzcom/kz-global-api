package kz.global.api.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

object PlayersTable : Table("player") {

    val steamid = varchar("steamid", 32)
    val lastNickname = varchar("last_nickname", 64)
    val ipAddress = varchar("ip_address", 45).nullable()
    val firstSeenAt = timestamp("first_seen_at").defaultExpression(CurrentTimestamp)
    val lastSeenAt = timestamp("last_seen_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(steamid)

}
