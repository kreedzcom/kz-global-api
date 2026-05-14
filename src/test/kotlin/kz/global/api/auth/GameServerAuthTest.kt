package kz.global.api.auth

import kz.global.api.db.tables.GameServersTable
import kz.global.api.support.TestDatabase
import kz.global.api.util.toHex
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameServerAuthTest {

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
    }

    @Test
    fun `valid 16-byte key for active server returns server id`() = runTest {
        val key = ByteArray(16) { it.toByte() }
        val serverId = transaction {
            GameServersTable.insert {
                it[name] = "auth-server"
                it[accessKey] = key
            }[GameServersTable.id]
        }

        val result = resolveGameServerToken(key.toHex())

        assertEquals(serverId, result)
    }

    @Test
    fun `valid key updates last_connected_at`() = runTest {
        val key = ByteArray(16) { (it + 10).toByte() }
        val serverId = transaction {
            GameServersTable.insert {
                it[name] = "auth-server-ts"
                it[accessKey] = key
            }[GameServersTable.id]
        }

        resolveGameServerToken(key.toHex())

        val lastConnected = transaction {
            GameServersTable.selectAll()
                .where { GameServersTable.id eq serverId }
                .single()[GameServersTable.lastConnectedAt]
        }
        assertNotNull(lastConnected)
    }

    @Test
    fun `returns null for non-hex string`() = runTest {
        val result = resolveGameServerToken("not-valid-hex!!!")

        assertNull(result)
    }

    @Test
    fun `returns null for odd-length hex string`() = runTest {
        val result = resolveGameServerToken("abc")

        assertNull(result)
    }

    @Test
    fun `returns null when key hex decodes to fewer than 16 bytes`() = runTest {
        val shortKey = ByteArray(8) { it.toByte() }.toHex()

        val result = resolveGameServerToken(shortKey)

        assertNull(result)
    }

    @Test
    fun `returns null when key hex decodes to more than 16 bytes`() = runTest {
        val longKey = ByteArray(32) { it.toByte() }.toHex()

        val result = resolveGameServerToken(longKey)

        assertNull(result)
    }

    @Test
    fun `returns null when no server matches the key`() = runTest {
        val unknownKey = ByteArray(16) { 0xFF.toByte() }.toHex()

        val result = resolveGameServerToken(unknownKey)

        assertNull(result)
    }

    @Test
    fun `returns null for inactive server even with correct key`() = runTest {
        val key = ByteArray(16) { (it + 5).toByte() }
        transaction {
            GameServersTable.insert {
                it[name] = "inactive-server"
                it[accessKey] = key
                it[active] = false
            }
        }

        val result = resolveGameServerToken(key.toHex())

        assertNull(result)
    }

    @Test
    fun `two servers with different keys resolve independently`() = runTest {
        val key1 = ByteArray(16) { it.toByte() }
        val key2 = ByteArray(16) { (it + 100).toByte() }
        val id1 = transaction {
            GameServersTable.insert {
                it[name] = "server-1"
                it[accessKey] = key1
            }[GameServersTable.id]
        }
        val id2 = transaction {
            GameServersTable.insert {
                it[name] = "server-2"
                it[accessKey] = key2
            }[GameServersTable.id]
        }

        assertEquals(id1, resolveGameServerToken(key1.toHex()))
        assertEquals(id2, resolveGameServerToken(key2.toHex()))
    }
}
