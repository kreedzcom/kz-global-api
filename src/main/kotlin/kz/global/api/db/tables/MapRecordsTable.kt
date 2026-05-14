package kz.global.api.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

object MapRecordsTable : Table("map_record") {

    val id = uuid("id")
    val serverId = integer("server_id").references(GameServersTable.id)
    val playerSteamid = varchar("player_steamid", 32).references(PlayersTable.steamid)
    val mapName = varchar("map_name", 255).references(MapsTable.name)
    val timeMs = long("time_ms")
    val checkpoints = integer("checkpoints")
    val gochecks = integer("gochecks")
    val localUid = varchar("local_uid", 64).uniqueIndex()
    val replayR2Key = varchar("replay_r2_key", 255).nullable()
    val flagged = bool("flagged").default(false)
    val reviewed = bool("reviewed").default(false)
    val pluginVersionId = integer("plugin_version_id").references(PluginVersionsTable.id)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, playerSteamid, mapName)
        index(false, mapName)
        index(false, timeMs)
    }

}
