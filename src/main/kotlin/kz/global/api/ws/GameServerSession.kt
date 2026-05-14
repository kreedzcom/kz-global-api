package kz.global.api.ws

import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

data class ConnectedPlayer(
    val steamid: String,
    val nickname: String,
)

class GameServerSession(
    val serverId: Int,
    val socket: DefaultWebSocketSession,
) {
    /** Set by [HelloHandler] after plugin version + checksum validation; required for ADD_RECORD. */
    @Volatile var pluginVersionId: Int = 0

    @Volatile var currentMap: String = ""
    private val _players = mutableMapOf<String, ConnectedPlayer>()
    private val mutex = Mutex()

    suspend fun addPlayer(player: ConnectedPlayer) = mutex.withLock { _players[player.steamid] = player }
    suspend fun removePlayer(steamid: String) = mutex.withLock { _players.remove(steamid) }
    suspend fun players(): List<ConnectedPlayer> = mutex.withLock { _players.values.toList() }

    suspend inline fun <reified T> sendJson(msgType: Int, msgId: Long = 0, payload: T) {
        val data = Json.encodeToJsonElement(payload)
        val envelope = WsEnvelope(msgType = msgType, msgId = msgId, data = data)
        socket.send(Frame.Text(Json.encodeToString(envelope)))
    }

    suspend fun sendError(msgId: Long = 0, message: String) {
        sendJson(MsgType.ERROR, msgId, ErrorPayload(message))
    }
}
