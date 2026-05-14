package kz.global.api.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kz.global.api.ws.ConnectedServersRegistry

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

    init {
        Gauge.builder("kz_connected_servers") { serversRegistry.connectedCount() }
            .description("Number of game servers currently connected via WebSocket")
            .register(meterRegistry)
    }
}
