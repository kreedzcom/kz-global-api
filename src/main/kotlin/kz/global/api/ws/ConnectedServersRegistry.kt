package kz.global.api.ws

import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class ConnectedServersRegistry {

    private val log = LoggerFactory.getLogger(ConnectedServersRegistry::class.java)
    private val sessions = ConcurrentHashMap<Int, GameServerSession>()

    fun register(session: GameServerSession) {
        sessions[session.serverId] = session
        log.info("Server {} connected. Total: {}", session.serverId, sessions.size)
    }

    fun unregister(serverId: Int) {
        sessions.remove(serverId)
        log.info("Server {} disconnected. Total: {}", serverId, sessions.size)
    }

    fun get(serverId: Int): GameServerSession? = sessions[serverId]

    fun allSessions(): Collection<GameServerSession> = sessions.values

    fun sessionsOnMap(mapName: String): List<GameServerSession> =
        sessions.values.filter { it.currentMap == mapName }

    fun connectedCount(): Int = sessions.size

    /** Closes a specific server session (e.g. on revoke). */
    suspend fun disconnect(serverId: Int) {
        sessions[serverId]?.socket?.close(CloseReason(CloseReason.Codes.NORMAL, "Server revoked"))
        unregister(serverId)
    }

    /** Closes all sessions — called on graceful shutdown. */
    suspend fun closeAll() {
        log.info("Closing {} WebSocket sessions...", sessions.size)
        val reason = CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down")
        val snapshot = sessions.values.toList()
        for (session in snapshot) {
            runCatching { session.socket.close(reason) }
                .onFailure { e -> log.warn("Failed to close session {}: {}", session.serverId, e.message) }
        }
        sessions.clear()
    }

}
