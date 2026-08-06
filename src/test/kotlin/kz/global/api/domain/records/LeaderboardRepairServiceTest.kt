package kz.global.api.domain.records

import kz.global.api.db.tables.*
import kz.global.api.support.TestDatabase
import kz.global.api.util.uuidV7
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderboardRepairServiceTest {

    private val repairService = LeaderboardRepairService()

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
                it[name] = "repair-server"
                it[accessKey] = ByteArray(16)
            }[GameServersTable.id]
            pluginVersionId = PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = ByteArray(16)
                it[checksumWindows] = ByteArray(16)
            }[PluginVersionsTable.id]
        }
    }

    @Test
    fun `repair promotes next fastest pro WR after WR delete`() {
        val wrId = insertRecord("kz_wr", "STEAM_0:0:1", 20_000L, 0, 0, "uid-wr")
        val nextId = insertRecord("kz_wr", "STEAM_0:0:2", 25_000L, 0, 0, "uid-next")
        setWr("kz_wr", "pro", wrId)

        transaction {
            MapRecordsTable.deleteWhere { MapRecordsTable.id eq wrId }
            val (prevPro, prevNub) = repairService.captureWrIds("kz_wr")
            val snapshot = RecordSnapshot(
                id = wrId,
                playerSteamid = "STEAM_0:0:1",
                mapName = "kz_wr",
                timeMs = 20_000L,
                gochecks = 0,
                localUid = "uid-wr",
                flagged = false,
                replayR2Key = null,
            )
            val result = repairService.repairForRecord(
                snapshot,
                invalidated = true,
                previousWrProId = prevPro,
                previousWrNubId = prevNub,
            )

            assertEquals(nextId, result.newWrProId)
            assertTrue(result.wrProChanged)
        }
    }

    @Test
    fun `repair removes WR when last pro run deleted`() {
        val wrId = insertRecord("kz_empty", "STEAM_0:0:1", 20_000L, 0, 0, "uid-only")
        setWr("kz_empty", "pro", wrId)

        transaction {
            val (prevPro, _) = repairService.captureWrIds("kz_empty")
            MapRecordsTable.deleteWhere { MapRecordsTable.id eq wrId }
            val snapshot = RecordSnapshot(
                id = wrId,
                playerSteamid = "STEAM_0:0:1",
                mapName = "kz_empty",
                timeMs = 20_000L,
                gochecks = 0,
                localUid = "uid-only",
                flagged = false,
                replayR2Key = null,
            )
            val result = repairService.repairForRecord(
                snapshot,
                invalidated = true,
                previousWrProId = prevPro,
            )

            assertNull(result.newWrProId)
            assertTrue(result.wrProChanged)
            assertEquals(0L, WorldRecordsTable.selectAll().count())
        }
    }

    @Test
    fun `repair excludes flagged records from WR`() {
        val flaggedWr = insertRecord("kz_flag", "STEAM_0:0:1", 20_000L, 0, 0, "uid-flagged", flagged = true)
        val cleanId = insertRecord("kz_flag", "STEAM_0:0:2", 30_000L, 0, 0, "uid-clean")
        setWr("kz_flag", "pro", flaggedWr)

        transaction {
            val (prevPro, _) = repairService.captureWrIds("kz_flag")
            MapRecordsTable.update({ MapRecordsTable.id eq flaggedWr }) {
                it[flagged] = true
            }
            val snapshot = loadSnapshot(flaggedWr)!!
            val result = repairService.repairForRecord(
                snapshot,
                invalidated = true,
                previousWrProId = prevPro,
            )

            assertEquals(cleanId, result.newWrProId)
        }
    }

    @Test
    fun `repair rebuilds player PB after slower run remains`() {
        val pbId = insertRecord("kz_pb", "STEAM_0:0:1", 30_000L, 0, 0, "uid-pb")
        val slowerId = insertRecord("kz_pb", "STEAM_0:0:1", 40_000L, 0, 0, "uid-slower")
        setBestPro("STEAM_0:0:1", "kz_pb", pbId)

        transaction {
            MapRecordsTable.deleteWhere { MapRecordsTable.id eq pbId }
            val snapshot = RecordSnapshot(
                id = pbId,
                playerSteamid = "STEAM_0:0:1",
                mapName = "kz_pb",
                timeMs = 30_000L,
                gochecks = 0,
                localUid = "uid-pb",
                flagged = false,
                replayR2Key = null,
            )
            repairService.repairForRecord(snapshot, invalidated = true)

            val best = BestProRecordsTable.selectAll()
                .where {
                    (BestProRecordsTable.playerSteamid eq "STEAM_0:0:1") and
                        (BestProRecordsTable.mapName eq "kz_pb")
                }
                .single()
            assertEquals(slowerId, best[BestProRecordsTable.recordId])
        }
    }

    @Test
    fun `repair is idempotent when run twice`() {
        val wrId = insertRecord("kz_idem", "STEAM_0:0:1", 20_000L, 0, 0, "uid-wr")
        val nextId = insertRecord("kz_idem", "STEAM_0:0:2", 25_000L, 0, 0, "uid-next")
        setWr("kz_idem", "pro", wrId)

        transaction {
            val (prevPro, _) = repairService.captureWrIds("kz_idem")
            MapRecordsTable.deleteWhere { MapRecordsTable.id eq wrId }
            val snapshot = RecordSnapshot(
                id = wrId,
                playerSteamid = "STEAM_0:0:1",
                mapName = "kz_idem",
                timeMs = 20_000L,
                gochecks = 0,
                localUid = "uid-wr",
                flagged = false,
                replayR2Key = null,
            )
            repairService.repairForRecord(
                snapshot,
                invalidated = true,
                previousWrProId = prevPro,
            )
            val second = repairService.repairForRecord(snapshot, invalidated = true)

            assertEquals(nextId, second.newWrProId)
            assertFalse(second.wrProChanged)
        }
    }

    private fun loadSnapshot(recordId: Uuid): RecordSnapshot? = transaction {
        MapRecordsTable.selectAll()
            .where { MapRecordsTable.id eq recordId }
            .singleOrNull()
            ?.let { row ->
                RecordSnapshot(
                    id = row[MapRecordsTable.id],
                    playerSteamid = row[MapRecordsTable.playerSteamid],
                    mapName = row[MapRecordsTable.mapName],
                    timeMs = row[MapRecordsTable.timeMs],
                    gochecks = row[MapRecordsTable.gochecks],
                    localUid = row[MapRecordsTable.localUid],
                    flagged = row[MapRecordsTable.flagged],
                    replayR2Key = row[MapRecordsTable.replayR2Key],
                )
            }
    }

    private fun insertRecord(
        map: String,
        steamid: String,
        timeMs: Long,
        checkpoints: Int,
        gochecks: Int,
        localUid: String,
        flagged: Boolean = false,
    ): Uuid {
        val id = uuidV7()
        val srvId = serverId
        val pvId = pluginVersionId
        transaction {
            MapsTable.insertIgnore { it[name] = map }
            PlayersTable.upsert(PlayersTable.steamid) {
                it[PlayersTable.steamid] = steamid
                it[lastNickname] = "Player"
            }
            MapRecordsTable.insert {
                it[MapRecordsTable.id] = id
                it[MapRecordsTable.serverId] = srvId
                it[playerSteamid] = steamid
                it[mapName] = map
                it[MapRecordsTable.timeMs] = timeMs
                it[MapRecordsTable.checkpoints] = checkpoints
                it[MapRecordsTable.gochecks] = gochecks
                it[MapRecordsTable.localUid] = localUid
                it[MapRecordsTable.pluginVersionId] = pvId
                it[MapRecordsTable.flagged] = flagged
            }
        }
        return id
    }

    private fun setWr(map: String, category: String, recordId: Uuid) {
        transaction {
            WorldRecordsTable.insert {
                it[mapName] = map
                it[WorldRecordsTable.category] = category
                it[WorldRecordsTable.recordId] = recordId
            }
        }
    }

    private fun setBestPro(steamid: String, map: String, recordId: Uuid) {
        transaction {
            BestProRecordsTable.insert {
                it[BestProRecordsTable.playerSteamid] = steamid
                it[BestProRecordsTable.mapName] = map
                it[BestProRecordsTable.recordId] = recordId
            }
        }
    }
}
