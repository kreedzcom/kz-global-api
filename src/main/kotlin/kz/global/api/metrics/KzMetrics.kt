package kz.global.api.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import kz.global.api.ws.ConnectedServersRegistry

object RecordRejectReason {
    const val BANNED = "banned"
    const val WR_RATIO = "wr_ratio"
    const val BELOW_MIN_TIME = "below_min_time"
    const val DUPLICATE = "duplicate"
}

/**
 * Central registry for all custom KZ business metrics.
 *
 * Naming follows the Prometheus convention: lowercase, underscore-separated,
 * with a `kz_` prefix to avoid collisions with Ktor/JVM built-ins.
 */
class KzMetrics(
    private val meterRegistry: MeterRegistry,
    private val serversRegistry: ConnectedServersRegistry,
) {
    // --- counters -----------------------------------------------------------

    val recordsSubmitted: Counter = Counter.builder("kz_records_submitted_total")
        .description("Total number of records accepted and persisted")
        .register(meterRegistry)

    val worldRecords: Counter = Counter.builder("kz_world_records_total")
        .description("Total number of world records set since startup")
        .register(meterRegistry)

    val replayUploads: Counter = Counter.builder("kz_replay_uploads_total")
        .description("Total number of replay files successfully stored in R2")
        .register(meterRegistry)

    val replayUploadFailures: Counter = Counter.builder("kz_replay_upload_failures_total")
        .description("Total number of failed replay uploads (checksum mismatch or R2 error)")
        .register(meterRegistry)

    val authFailures: Counter = Counter.builder("kz_ws_auth_failures_total")
        .description("Total number of WebSocket upgrade rejections due to bad token or cutoff plugin")
        .register(meterRegistry)

    val flaggedRecords: Counter = Counter.builder("kz_flagged_records_total")
        .description("Total number of records flagged for admin review")
        .register(meterRegistry)

    // --- timers -------------------------------------------------------------

    val recordPersistLatency: Timer = Timer.builder("kz_record_persist_duration_seconds")
        .description("Time spent persisting a single record (DB transaction)")
        .register(meterRegistry)

    // --- gauges (live state) ------------------------------------------------

    private val playersByServerGauge = MultiGauge.builder("kz_connected_players_by_server")
        .description("Players currently connected per game server")
        .register(meterRegistry)

    init {
        Gauge.builder("kz_connected_servers") { serversRegistry.connectedCount().toDouble() }
            .description("Number of game servers currently connected via WebSocket")
            .register(meterRegistry)

        Gauge.builder("kz_connected_players") { serversRegistry.connectedPlayerCount().toDouble() }
            .description("Total players currently connected across all game servers")
            .register(meterRegistry)

        serversRegistry.setSessionsChangedListener { refreshPlayersByServerGauge() }
        refreshPlayersByServerGauge()
    }

    fun recordPlayerJoin(serverId: Int, banned: Boolean) {
        meterRegistry.counter(
            "kz_player_joins_total",
            "server_id",
            serverId.toString(),
            "banned",
            banned.toString(),
        ).increment()
        refreshPlayersByServerGauge()
    }

    fun recordPlayerLeave(serverId: Int) {
        meterRegistry.counter(
            "kz_player_leaves_total",
            "server_id",
            serverId.toString(),
        ).increment()
        refreshPlayersByServerGauge()
    }

    fun recordRejected(reason: String) {
        meterRegistry.counter(
            "kz_records_rejected_total",
            "reason",
            reason,
        ).increment()
    }

    private fun refreshPlayersByServerGauge() {
        val rows = serversRegistry.allSessions().map { session ->
            MultiGauge.Row.of(
                Tags.of("server_id", session.serverId.toString()),
                session.playerCount,
            )
        }
        playersByServerGauge.register(rows, true)
    }
}
