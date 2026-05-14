package kz.global.api.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

object MapMinimumTimesTable : Table("map_minimum_time") {

    val mapName = varchar("map_name", 255).references(MapsTable.name)
    val minTimeMs = long("min_time_ms")
    val updatedBy = varchar("updated_by", 255)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(mapName)

}
