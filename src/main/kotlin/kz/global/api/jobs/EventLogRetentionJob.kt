package kz.global.api.jobs

import kz.global.api.config.SecurityConfig
import kz.global.api.db.tables.EventLogTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class EventLogRetentionJob(
    private val scope: CoroutineScope,
    private val config: SecurityConfig,
) {

    private val log = LoggerFactory.getLogger(EventLogRetentionJob::class.java)

    fun start() {
        scope.launch {
            while (isActive) {
                runCatching { purgeOldEvents() }
                    .onFailure { e -> log.warn("Event log retention failed: {}", e.message) }
                delay(24.hours)
            }
        }
    }

    private suspend fun purgeOldEvents() {
        val cutoff = Clock.System.now().minus(config.eventLogRetentionDays.days)
        val deleted = suspendTransaction {
            EventLogTable.deleteWhere { EventLogTable.createdAt less cutoff }
        }
        if (deleted > 0) {
            log.info("Purged {} event_log rows older than {} days", deleted, config.eventLogRetentionDays)
        }
    }

}
