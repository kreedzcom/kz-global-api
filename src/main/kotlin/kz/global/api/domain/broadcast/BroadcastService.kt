package kz.global.api.domain.broadcast

import kz.global.api.db.tables.*
import kz.global.api.events.KzEventBus
import kz.global.api.ws.ConnectedServersRegistry
import kz.global.api.ws.MapInfoPayload
import kz.global.api.ws.MsgType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

class BroadcastService(
    private val registry: ConnectedServersRegistry,
    private val eventBus: KzEventBus,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    scope: CoroutineScope = CoroutineScope(ioDispatcher),
) {

    private val log = LoggerFactory.getLogger(BroadcastService::class.java)

    init {
        scope.launch { subscribeToEvents() }
    }

    private suspend fun subscribeToEvents() {
        eventBus.events.collect { event ->
            when (event) {
                is kz.global.api.events.KzEvent.NewWorldRecord -> broadcastNewWr(event.mapName)
                else -> {}
            }
        }
    }

    private suspend fun broadcastNewWr(mapName: String) {
        val mapInfo = getMapInfo(mapName) ?: return
        val sessions = registry.sessionsOnMap(mapName)
        log.info("Broadcasting new WR for '{}' to {} server(s)", mapName, sessions.size)
        sessions.forEach { session ->
            runCatching { session.sendJson(MsgType.MAP_INFO, 0, mapInfo) }
                .onFailure { log.warn("Failed to broadcast WR to server {}: {}", session.serverId, it.message) }
        }
    }

    suspend fun getMapInfo(mapName: String): MapInfoPayload? = suspendTransaction() {
        val mapExists = MapsTable.selectAll().where { MapsTable.name eq mapName }.count() > 0
        if (!mapExists) return@suspendTransaction null

        val nubWr = fetchWr(mapName, "nub")
        val proWr = fetchWr(mapName, "pro")

        MapInfoPayload(
            mapName = mapName,
            wrNubSteamid = nubWr?.first,
            wrNubTimeMs = nubWr?.second,
            wrProSteamid = proWr?.first,
            wrProTimeMs = proWr?.second,
        )
    }

    private fun fetchWr(mapName: String, category: String): Pair<String, Long>? {
        return (WorldRecordsTable innerJoin MapRecordsTable)
            .selectAll()
            .where {
                (WorldRecordsTable.mapName eq mapName) and
                (WorldRecordsTable.category eq category)
            }
            .singleOrNull()
            ?.let { row ->
                row[MapRecordsTable.playerSteamid] to row[MapRecordsTable.timeMs]
            }
    }

}
