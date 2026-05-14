package kz.global.api.ws.handlers

import io.ktor.websocket.*
import kz.global.api.db.tables.PluginVersionsTable
import kz.global.api.domain.broadcast.BroadcastService
import kz.global.api.events.AuditLogger
import kz.global.api.util.fromHex
import kz.global.api.ws.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

class HelloHandler(
    private val broadcastService: BroadcastService,
    private val auditLogger: AuditLogger,
) {

    private val log = LoggerFactory.getLogger(HelloHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(session: GameServerSession, envelope: WsEnvelope) {
        val payload = json.decodeFromJsonElement(HelloPayload.serializer(), envelope.data)

        val checksumBytes = runCatching { payload.pluginChecksum.fromHex() }.getOrNull()
        if (checksumBytes == null || checksumBytes.size != 16) {
            session.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid plugin checksum format"))
            return
        }

        val pluginVersion = suspendTransaction {
            PluginVersionsTable
                .selectAll()
                .where {
                    (PluginVersionsTable.semver eq payload.pluginVersion) and
                    (PluginVersionsTable.checksumLinux eq checksumBytes)
                }
                .singleOrNull()
        }

        if (pluginVersion == null) {
            log.warn("Server {}: unknown plugin version {} / checksum {}", session.serverId, payload.pluginVersion, payload.pluginChecksum)
            session.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unknown plugin version or checksum"))
            return
        }

        if (pluginVersion[PluginVersionsTable.isCutoff]) {
            log.warn("Server {}: plugin version {} is cutoff", session.serverId, payload.pluginVersion)
            session.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Plugin version no longer supported, please update"))
            return
        }

        session.currentMap = payload.mapName
        log.info("Server {} hello: plugin={} map={}", session.serverId, payload.pluginVersion, payload.mapName)

        val mapInfo = broadcastService.getMapInfo(payload.mapName)
        session.sendJson(MsgType.HELLO_ACK, envelope.msgId, HelloAckPayload(mapInfo = mapInfo))

        auditLogger.log("SERVER_CONNECTED", session.serverId,
            "plugin_version" to payload.pluginVersion,
            "map_name" to payload.mapName,
        )
    }

}
