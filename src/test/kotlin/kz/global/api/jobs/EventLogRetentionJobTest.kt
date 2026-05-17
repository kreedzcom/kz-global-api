package kz.global.api.jobs

import kz.global.api.db.tables.EventLogTable
import kz.global.api.support.TestDatabase
import kz.global.api.support.testSecurityConfig
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventLogRetentionJobTest {

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
    }

    @Test
    fun `purgeOldEvents deletes rows older than retention`() = runTest {
        val old = Clock.System.now().minus(100.days)
        val recent = Clock.System.now()
        transaction {
            EventLogTable.insert {
                it[eventType] = "OLD"
                it[payload] = "{}"
                it[createdAt] = old
            }
            EventLogTable.insert {
                it[eventType] = "RECENT"
                it[payload] = "{}"
                it[createdAt] = recent
            }
        }

        val job = EventLogRetentionJob(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            config = testSecurityConfig(eventLogRetentionDays = 90),
        )
        job.purgeOldEvents()

        transaction {
            val remaining = EventLogTable.selectAll().map { it[EventLogTable.eventType] }
            assertEquals(listOf("RECENT"), remaining)
        }
    }

}
