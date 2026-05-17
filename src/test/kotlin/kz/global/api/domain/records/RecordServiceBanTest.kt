package kz.global.api.domain.records

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kz.global.api.db.tables.*
import kz.global.api.domain.players.PlayerBanService
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEventBus
import kz.global.api.metrics.KzMetrics
import kz.global.api.support.TestDatabase
import kz.global.api.support.testSecurityConfig
import kz.global.api.ws.AddRecordPayload
import kz.global.api.ws.ConnectedServersRegistry
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.eq
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordServiceBanTest {

    private val banService = PlayerBanService()
    private val service = RecordService(
        KzEventBus(),
        mockk(relaxed = true),
        KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
        banService,
        testSecurityConfig(),
    )

    private var serverId = 0
    private var pluginVersionId = 0

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun setup() {
        TestDatabase.truncateAll()
        transaction {
            serverId = GameServersTable.insert {
                it[name] = "ban-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
            pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
            PlayersTable.insert {
                it[steamid] = "STEAM_0:0:99999"
                it[lastNickname] = "Banned"
                it[isBanned] = true
            }
        }
    }

    @Test
    fun `submit rejects banned player`() = runTest {
        val payload = AddRecordPayload("STEAM_0:0:99999", "kz_test", 30_000L, "uid-ban", 0, 0)

        val result = service.submit(serverId, pluginVersionId, payload)

        assertIs<RecordResult.Rejected>(result)
        assertTrue(result.reason.contains("banned", ignoreCase = true))
    }

}
