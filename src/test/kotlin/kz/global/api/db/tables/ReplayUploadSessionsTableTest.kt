package kz.global.api.db.tables

import kz.global.api.support.TestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplayUploadSessionsTableTest {

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
    }

    @Test
    fun `replay upload session row can be inserted and read`() {
        val serverId = transaction {
            GameServersTable.insert {
                it[name] = "replay-session-server"
                it[accessKey] = ByteArray(16) { 7 }
            }[GameServersTable.id]
        }

        transaction {
            ReplayUploadSessionsTable.insert {
                it[localUid] = "local-uid-replay-test"
                it[ReplayUploadSessionsTable.serverId] = serverId
                it[totalChunks] = 12
            }
        }

        val received = transaction {
            ReplayUploadSessionsTable.selectAll()
                .where { ReplayUploadSessionsTable.localUid eq "local-uid-replay-test" }
                .single()[ReplayUploadSessionsTable.receivedChunks]
        }

        assertEquals(0, received)
        val total = transaction {
            ReplayUploadSessionsTable.selectAll()
                .where { ReplayUploadSessionsTable.localUid eq "local-uid-replay-test" }
                .single()[ReplayUploadSessionsTable.totalChunks]
        }
        assertEquals(12, total)
    }
}
