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
import kz.global.api.util.uuidV7
import kz.global.api.ws.AddRecordPayload
import kz.global.api.ws.ConnectedServersRegistry
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordServiceWrRatioTest {

    private val service = RecordService(
        KzEventBus(),
        mockk(relaxed = true),
        KzMetrics(SimpleMeterRegistry(), ConnectedServersRegistry()),
        PlayerBanService(),
        testSecurityConfig(maxWrImprovementRatio = 0.5),
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
                it[name] = "wr-ratio-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
            pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
            MapsTable.insertIgnore { it[name] = "kz_ratio" }
            PlayersTable.insertIgnore {
                it[steamid] = "STEAM_0:0:1"
                it[lastNickname] = "WRHolder"
            }
            val wrId = uuidV7()
            val sid = serverId
            val pvid = pluginVersionId
            MapRecordsTable.insert {
                it[id] = wrId
                it[MapRecordsTable.serverId] = sid
                it[playerSteamid] = "STEAM_0:0:1"
                it[mapName] = "kz_ratio"
                it[timeMs] = 100_000L
                it[checkpoints] = 0
                it[gochecks] = 0
                it[localUid] = "wr-existing"
                it[MapRecordsTable.pluginVersionId] = pvid
            }
            WorldRecordsTable.insert {
                it[mapName] = "kz_ratio"
                it[category] = "pro"
                it[recordId] = wrId
            }
        }
    }

    @Test
    fun `submit rejects run that improves WR by more than configured ratio`() = runTest {
        val payload = AddRecordPayload("STEAM_0:0:2", "kz_ratio", 40_000L, "uid-too-fast", 0, 0)

        val result = service.submit(serverId, pluginVersionId, payload)

        assertIs<RecordResult.Rejected>(result)
        assertTrue(result.reason.contains("ratio", ignoreCase = true))
    }

}
