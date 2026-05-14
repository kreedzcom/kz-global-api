package kz.global.api.db.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

object ReplayUploadSessionsTable : Table("replay_upload_session") {

    val localUid = varchar("local_uid", 64)
    val serverId = integer("server_id").references(GameServersTable.id)
    val receivedChunks = integer("received_chunks").default(0)
    val totalChunks = integer("total_chunks")
    val startedAt = timestamp("started_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(localUid)

}
