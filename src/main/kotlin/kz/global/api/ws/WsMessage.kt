package kz.global.api.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ─── Message type constants ─────────────────────────────────────────────────

object MsgType {
    // Inbound (plugin → API)
    const val HELLO = 1
    const val MAP_CHANGE = 2
    const val PLAYER_JOIN = 3
    const val PLAYER_LEAVE = 4
    const val WANT_MAP_INFO = 5
    const val WANT_COURSE_TOP = 6
    const val WANT_PLAYER_RECORDS = 7
    const val ADD_RECORD = 8

    // Outbound (API → plugin)
    const val HELLO_ACK = 101
    const val MAP_INFO = 102
    const val COURSE_TOP = 103
    const val PLAYER_RECORDS = 104
    const val RECORD_ACK = 105
    const val FILE_ACK = 106
    const val WR_BROADCAST = 107
    const val ERROR = 199
}

// ─── Envelope ────────────────────────────────────────────────────────────────

@Serializable
data class WsEnvelope(
    @SerialName("msg_type") val msgType: Int,
    @SerialName("msg_id") val msgId: Long = 0,
    val data: JsonElement = JsonObject(emptyMap()),
)

// ─── Inbound payloads ────────────────────────────────────────────────────────

@Serializable
data class HelloPayload(
    @SerialName("plugin_version") val pluginVersion: String,
    @SerialName("plugin_checksum") val pluginChecksum: String,
    @SerialName("map_name") val mapName: String,
)

@Serializable
data class MapChangePayload(
    @SerialName("map_name") val mapName: String,
)

@Serializable
data class PlayerJoinPayload(
    val steamid: String,
    val nickname: String,
    @SerialName("ip_address") val ipAddress: String? = null,
)

@Serializable
data class PlayerLeavePayload(
    val steamid: String,
)

@Serializable
data class WantMapInfoPayload(
    @SerialName("map_name") val mapName: String,
)

@Serializable
data class WantCourseTopPayload(
    @SerialName("map_name") val mapName: String,
    val category: String = "nub",
    val limit: Int = 10,
    val offset: Int = 0,
)

@Serializable
data class WantPlayerRecordsPayload(
    val steamid: String,
    @SerialName("map_name") val mapName: String,
)

@Serializable
data class AddRecordPayload(
    val steamid: String,
    @SerialName("map_name") val mapName: String,
    @SerialName("time_ms") val timeMs: Long,
    @SerialName("local_uid") val localUid: String,
    /** Checkpoint count (aggregate touches; not split times). Required; if [gochecks] is positive, this must be positive too. */
    val checkpoints: Int,
    /** Go-check count; `0` = eligible for pro leaderboard as well as nub. */
    val gochecks: Int,
)

// ─── Outbound payloads ───────────────────────────────────────────────────────

@Serializable
data class HelloAckPayload(
    @SerialName("heartbeat_interval") val heartbeatInterval: Int = 30,
    @SerialName("map_info") val mapInfo: MapInfoPayload? = null,
)

@Serializable
data class MapInfoPayload(
    @SerialName("map_name") val mapName: String,
    @SerialName("wr_nub_steamid") val wrNubSteamid: String? = null,
    @SerialName("wr_nub_time_ms") val wrNubTimeMs: Long? = null,
    @SerialName("wr_pro_steamid") val wrProSteamid: String? = null,
    @SerialName("wr_pro_time_ms") val wrProTimeMs: Long? = null,
)

@Serializable
data class RecordAckPayload(
    val id: String,
    @SerialName("local_uid") val localUid: String,
    @SerialName("is_pb") val isPb: Boolean,
)

@Serializable
data class FileAckPayload(
    @SerialName("local_uid") val localUid: String,
    val status: Boolean,
)

@Serializable
data class PlayerJoinAckPayload(
    val steamid: String,
    @SerialName("is_banned") val isBanned: Boolean,
)

@Serializable
data class CourseTopEntry(
    val rank: Int,
    val steamid: String,
    val nickname: String,
    @SerialName("time_ms") val timeMs: Long,
    val checkpoints: Int,
    val gochecks: Int,
)

@Serializable
data class CourseTopPayload(
    @SerialName("map_name") val mapName: String,
    val category: String,
    val entries: List<CourseTopEntry>,
)

@Serializable
data class PlayerRecordEntry(
    @SerialName("map_name") val mapName: String,
    @SerialName("time_ms") val timeMs: Long,
    @SerialName("record_id") val recordId: String,
    val checkpoints: Int,
    val gochecks: Int,
)

@Serializable
data class PlayerRecordsPayload(
    val steamid: String,
    val records: List<PlayerRecordEntry>,
)

@Serializable
data class ErrorPayload(val message: String)
