package kz.global.api.db.tables

import org.jetbrains.exposed.v1.core.Table

object BestProRecordsTable : Table("best_pro_record") {

    val playerSteamid = varchar("player_steamid", 32).references(PlayersTable.steamid)
    val mapName = varchar("map_name", 255).references(MapsTable.name)
    val recordId = uuid("record_id").references(MapRecordsTable.id)

    override val primaryKey = PrimaryKey(playerSteamid, mapName)

}
