package kz.global.api.support

import io.ktor.websocket.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kz.global.api.ws.GameServerSession
import kz.global.api.ws.WsEnvelope

private val json = Json { ignoreUnknownKeys = true }

/**
 * Creates a [GameServerSession] backed by a relaxed mock socket.
 *
 * Returns the session alongside a lambda that decodes and returns all
 * outbound [WsEnvelope]s captured so far. Use it in handler tests to
 * assert what the API sends back to the plugin.
 *
 * ```kotlin
 * val (session, sent) = mockSession()
 * handler.handle(session, envelope)
 * assertEquals(MsgType.HELLO_ACK, sent().first().msgType)
 * ```
 */
fun mockSession(serverId: Int = 1): Pair<GameServerSession, () -> List<WsEnvelope>> {
    val captured = mutableListOf<String>()
    val socket = mockk<DefaultWebSocketSession>(relaxed = true)
    coEvery { socket.send(any<Frame>()) } coAnswers {
        val frame = firstArg<Frame>()
        if (frame is Frame.Text) captured.add(frame.readText())
    }
    val session = GameServerSession(serverId, socket)
    return session to { captured.map { json.decodeFromString(it) } }
}
