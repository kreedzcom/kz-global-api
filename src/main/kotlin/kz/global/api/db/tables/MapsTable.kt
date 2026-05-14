package kz.global.api.db.tables

import org.jetbrains.exposed.v1.core.Table

object MapsTable : Table("map") {

    val name = varchar("name", 255)
    val checksum = varchar("checksum", 64).nullable()
    val type = varchar("type", 32).nullable()
    val length = float("length").nullable()
    val difficulty = integer("difficulty").nullable()

    override val primaryKey = PrimaryKey(name)

}
