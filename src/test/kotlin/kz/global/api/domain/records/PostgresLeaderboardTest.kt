package kz.global.api.domain.records

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kz.global.api.db.tables.*
import kz.global.api.domain.players.PlayerBanService
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEventBus
import kz.global.api.metrics.KzMetrics
import kz.global.api.support.testSecurityConfig
import kz.global.api.ws.AddRecordPayload
import kz.global.api.ws.ConnectedServersRegistry
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Tag
import kotlin.test.*

@Tag("postgres")
class PostgresLeaderboardTest {

    @Test
    fun `migrations and concurrent submissions and finalization work on PostgreSQL`() = runBlocking {
        val url = checkNotNull(System.getenv("KZ_TEST_POSTGRES_URL"))
        val user = checkNotNull(System.getenv("KZ_TEST_POSTGRES_USER"))
        val password = checkNotNull(System.getenv("KZ_TEST_POSTGRES_PASSWORD"))
        val flyway = Flyway.configure().dataSource(url, user, password).load()
        flyway.migrate()
        val database = Database.connect(url, "org.postgresql.Driver", user, password)
        val (firstServer, secondServer, version) = transaction(database) {
            val serverIds = (1..2).map { number ->
                GameServersTable.insert {
                    it[name] = "postgres-test-$number"
                    it[accessKey] = ByteArray(16) { number.toByte() }
                }[GameServersTable.id]
            }
            val pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
            Triple(serverIds[0], serverIds[1], pluginVersionId)
        }
        val service = RecordService(
            KzEventBus(),
            mockk<AuditLogger>(relaxed = true),
            KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
            PlayerBanService(AuditLogger()),
            testSecurityConfig(),
        )
        val fast = AddRecordPayload("STEAM_0:0:11", "kz_pg", 20_000, "shared", 0, 0)
        val slow = fast.copy(timeMs = 25_000)

        val submissions = listOf(
            async { service.submit(firstServer, version, fast) },
            async { service.submit(firstServer, version, fast) },
            async { service.submit(secondServer, version, slow) },
        ).awaitAll()
        val fastId = submissions.take(2).filterIsInstance<RecordResult.Accepted>().single().recordId
        val slowId = assertIs<RecordResult.Accepted>(submissions.last()).recordId
        val results = listOf(fastId, fastId, slowId).map { id ->
            async { service.finalizePendingLeaderboard(id, "replays/$id.krpz") }
        }.awaitAll()

        assertEquals(RecordResult.Duplicate(fastId), submissions.take(2).filterIsInstance<RecordResult.Duplicate>().single())
        assertEquals(2, results.count { it })
        transaction(database) {
            assertEquals(2L, MapRecordsTable.selectAll().count())
            assertEquals(fastId, BestProRecordsTable.selectAll().single()[BestProRecordsTable.recordId])
            assertEquals(fastId, WorldRecordsTable.selectAll().single()[WorldRecordsTable.recordId])
        }

        val slower = slow.copy(localUid = "another", timeMs = 30_000)
        val slowerId = assertIs<RecordResult.Accepted>(service.submit(secondServer, version, slower)).recordId
        listOf(
            async { service.flagInvalidReplay(fastId, "test invalidation") },
            async { service.finalizePendingLeaderboard(slowerId, "replays/$slowerId.krpz") },
        ).awaitAll()

        transaction(database) {
            assertTrue(MapRecordsTable.selectAll().where { MapRecordsTable.id eq fastId }.single()[MapRecordsTable.flagged])
            assertEquals(slowId, BestProRecordsTable.selectAll().single()[BestProRecordsTable.recordId])
            assertEquals(slowId, WorldRecordsTable.selectAll().single()[WorldRecordsTable.recordId])
        }
    }
}
