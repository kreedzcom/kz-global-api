package kz.global.api.ws

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kz.global.api.auth.resolveGameServerToken
import kz.global.api.metrics.KzMetrics
import kz.global.api.security.WsRateLimiters
import kz.global.api.util.clientIp
import kz.global.api.ws.handlers.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("GameServerWsRoute")
private val json = Json { ignoreUnknownKeys = true }

fun Routing.gameServerWsRoute() {
    val registry by inject<ConnectedServersRegistry>()
    val metrics by inject<KzMetrics>()
    val rateLimiters by inject<WsRateLimiters>()
    val helloHandler by inject<HelloHandler>()
    val mapChangeHandler by inject<MapChangeHandler>()
    val playerJoinHandler by inject<PlayerJoinHandler>()
    val playerLeaveHandler by inject<PlayerLeaveHandler>()
    val mapInfoHandler by inject<MapInfoHandler>()
    val courseTopHandler by inject<CourseTopHandler>()
    val playerRecordsHandler by inject<PlayerRecordsHandler>()
    val addRecordHandler by inject<AddRecordHandler>()
    val replayChunkHandler by inject<ReplayChunkHandler>()

    webSocket("/ws/game") {
        val clientIp = call.clientIp()

        if (!rateLimiters.wsUpgradeByIp.tryAcquire(clientIp)) {
            metrics.authFailures.increment()
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Rate limit exceeded"))
            return@webSocket
        }

        val token = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim()

        if (token == null) {
            metrics.authFailures.increment()
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing Authorization header"))
            return@webSocket
        }

        val serverId = resolveGameServerToken(token, clientIp)
        if (serverId == null) {
            metrics.authFailures.increment()
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or inactive token"))
            return@webSocket
        }

        val session = GameServerSession(serverId, this)
        registry.register(session)

        try {
            incoming.consumeEach { frame ->
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        runCatching {
                            val envelope = json.decodeFromString<WsEnvelope>(text)
                            when (envelope.msgType) {
                                MsgType.HELLO -> helloHandler.handle(session, envelope)
                                MsgType.MAP_CHANGE -> mapChangeHandler.handle(session, envelope)
                                MsgType.PLAYER_JOIN -> playerJoinHandler.handle(session, envelope)
                                MsgType.PLAYER_LEAVE -> playerLeaveHandler.handle(session, envelope)
                                MsgType.WANT_MAP_INFO -> mapInfoHandler.handle(session, envelope)
                                MsgType.WANT_COURSE_TOP -> courseTopHandler.handle(session, envelope)
                                MsgType.WANT_PLAYER_RECORDS -> playerRecordsHandler.handle(session, envelope)
                                MsgType.ADD_RECORD -> addRecordHandler.handle(session, envelope)
                                else -> session.sendError(envelope.msgId, "Unknown msg_type: ${envelope.msgType}")
                            }
                        }.onFailure { e ->
                            log.warn("Server {}: failed to handle message: {}", serverId, e.message)
                            session.sendError(message = "Invalid message format")
                        }
                    }
                    is Frame.Binary -> {
                        runCatching { replayChunkHandler.handle(session, frame.readBytes()) }
                            .onFailure { e ->
                                log.warn("Server {}: failed to handle replay chunk: {}", serverId, e.message)
                            }
                    }
                    is Frame.Ping -> send(Frame.Pong(frame.buffer))
                    else -> {}
                }
            }
        } finally {
            registry.unregister(serverId)
        }
    }
}
