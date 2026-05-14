package kz.global.api.db.tables

import org.jetbrains.exposed.v1.core.Table

object WorldRecordsTable : Table("world_record") {

    val mapName = varchar("map_name", 255).references(MapsTable.name)
    val category = varchar("category", 3)
    val recordId = uuid("record_id").references(MapRecordsTable.id)

    override val primaryKey = PrimaryKey(mapName, category)

}
