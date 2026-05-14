package kz.global.api.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

object PluginVersionsTable : Table("plugin_version") {

    val id = integer("id").autoIncrement()
    val semver = varchar("semver", 50)
    val checksumLinux = binary("checksum_linux", 16)
    val checksumWindows = binary("checksum_windows", 16)
    val isCutoff = bool("is_cutoff").default(false)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)

}
