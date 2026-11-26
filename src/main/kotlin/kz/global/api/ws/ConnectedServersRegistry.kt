package kz.global.api.ws

import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class ConnectedServersRegistry {

    private val log = LoggerFactory.getLogger(ConnectedServersRegistry::class.java)
    private val sessions = ConcurrentHashMap<Int, GameServerSession>()
    private val registrationLock = Any()
    private val revokedServers = mutableSetOf<Int>()

    @Volatile
    private var draining = false

    fun isDraining(): Boolean = draining

    /** Stops accepting new WebSocket sessions; existing sessions stay open until closed. */
    fun beginDrain() {
        synchronized(registrationLock) { draining = true }
        log.info("Drain mode enabled — rejecting new WebSocket connections")
    }

    suspend fun register(session: GameServerSession): Boolean {
        val previous = synchronized(registrationLock) {
            if (draining || session.serverId in revokedServers) return false
            sessions.put(session.serverId, session)
        }
        if (previous != null && previous !== session) {
            closeSession(previous, CloseReason(CloseReason.Codes.GOING_AWAY, "Session replaced"))
        }
        log.info("Server {} connected. Total: {}", session.serverId, sessions.size)
        sessionsChangedListener?.invoke()
        return sessions[session.serverId] === session
    }

    fun unregister(session: GameServerSession) {
        if (sessions.remove(session.serverId, session)) {
            log.info("Server {} disconnected. Total: {}", session.serverId, sessions.size)
            sessionsChangedListener?.invoke()
        }
    }

    fun get(serverId: Int): GameServerSession? = sessions[serverId]

    fun allSessions(): Collection<GameServerSession> = sessions.values

    fun sessionsOnMap(mapName: String): List<GameServerSession> =
        sessions.values.filter { it.currentMap == mapName }

    fun connectedCount(): Int = sessions.size

    fun connectedPlayerCount(): Int = sessions.values.sumOf { it.playerCount }

    @Volatile
    private var sessionsChangedListener: (() -> Unit)? = null

    fun setSessionsChangedListener(listener: (() -> Unit)?) {
        sessionsChangedListener = listener
    }

    /** Called after deactivation commits. Rejects sessions authenticated before that commit. */
    suspend fun revoke(serverId: Int) {
        val current = synchronized(registrationLock) {
            revokedServers.add(serverId)
            sessions.remove(serverId)
        }
        if (current != null) {
            closeSession(current, CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Server revoked"))
            sessionsChangedListener?.invoke()
        }
    }

    /** Closes all sessions — called on graceful shutdown. */
    suspend fun closeAll() {
        log.info("Closing {} WebSocket sessions...", sessions.size)
        val reason = CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down")
        val snapshot = synchronized(registrationLock) {
            draining = true
            sessions.values.toList().also { sessions.clear() }
        }
        for (session in snapshot) {
            closeSession(session, reason)
        }
        sessionsChangedListener?.invoke()
    }

    private suspend fun closeSession(session: GameServerSession, reason: CloseReason) {
        runCatching { session.socket.close(reason) }
            .onFailure { e -> log.warn("Failed to close session {}: {}", session.serverId, e.message) }
    }

}
