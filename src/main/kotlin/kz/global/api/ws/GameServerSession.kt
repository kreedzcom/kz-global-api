package kz.global.api.ws

import kotlin.PublishedApi
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.atomic.AtomicInteger

/** Game plugins expect defaulted fields (e.g. `heartbeat_interval` on HELLO_ACK) to appear on the wire. */
@PublishedApi
internal val wsEncodeJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

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
    private val _playerCount = AtomicInteger(0)
    private val mutex = Mutex()

    val playerCount: Int get() = _playerCount.get()

    suspend fun addPlayer(player: ConnectedPlayer) = mutex.withLock {
        if (_players.put(player.steamid, player) == null) {
            _playerCount.incrementAndGet()
        }
    }

    suspend fun removePlayer(steamid: String) = mutex.withLock {
        if (_players.remove(steamid) != null) {
            _playerCount.decrementAndGet()
        }
    }

    suspend fun players(): List<ConnectedPlayer> = mutex.withLock { _players.values.toList() }

    suspend inline fun <reified T> sendJson(msgType: Int, msgId: Long = 0, payload: T) {
        val data = wsEncodeJson.encodeToJsonElement(payload)
        val envelope = WsEnvelope(msgType = msgType, msgId = msgId, data = data)
        socket.send(Frame.Text(wsEncodeJson.encodeToString(envelope)))
    }

    suspend fun sendError(msgId: Long, message: String) {
        sendJson(MsgType.ERROR, msgId, ErrorPayload(message))
    }
}
