package kz.global.api

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.ws
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kz.global.api.db.tables.GameServersTable
import kz.global.api.db.tables.PluginVersionsTable
import kz.global.api.support.TestDatabase
import kz.global.api.support.mapApplicationConfigForTests
import kz.global.api.util.toHex
import kz.global.api.ws.HelloPayload
import kz.global.api.ws.MsgType
import kz.global.api.ws.WsEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationIntegrationTest {

    @BeforeAll
    fun setupClass() {
        TestDatabase.connect()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
    }

    @Test
    fun `module exposes health`() = testApplication {
        environment {
            config = mapApplicationConfigForTests()
        }
        application {
            module()
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `module exposes ready when database is connected`() = testApplication {
        environment {
            config = mapApplicationConfigForTests()
        }
        application {
            module()
        }

        val response = client.get("/ready")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `module exposes prometheus metrics`() = testApplication {
        environment {
            config = mapApplicationConfigForTests()
        }
        application {
            module()
        }

        val unauthorized = client.get("/metrics")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val response = client.get("/metrics") {
            header(HttpHeaders.Authorization, "Bearer test-metrics-key")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains('#') || body.contains("jvm"))
    }

    @Test
    fun `game websocket accepts hello after valid token`() = testApplication {
        environment {
            config = mapApplicationConfigForTests()
        }
        application {
            module()
        }

        val checksum = ByteArray(16) { it.toByte() }
        val checksumHex = checksum.toHex()
        val accessKey = ByteArray(16) { (it + 3).toByte() }
        val tokenHex = accessKey.toHex()

        transaction {
            PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = checksum
                it[checksumWindows] = checksum
                it[isCutoff] = false
            }
            val keyBytes = accessKey
            GameServersTable.insert {
                it[name] = "ws-int-server"
                it[GameServersTable.accessKey] = keyBytes
            }
        }

        val json = Json { ignoreUnknownKeys = true }
        val envelope = WsEnvelope(
            msgType = MsgType.HELLO,
            msgId = 7L,
            data = json.encodeToJsonElement(
                HelloPayload(
                    pluginVersion = "1.0.0",
                    pluginChecksum = checksumHex,
                    mapName = "kz_ws_int",
                ),
            ),
        )
        val payload = json.encodeToString(WsEnvelope.serializer(), envelope)

        val client = createClient {
            install(WebSockets)
        }

        client.ws(
            path = "/ws/game",
            request = {
                header(HttpHeaders.Authorization, "Bearer $tokenHex")
            },
        ) {
            send(Frame.Text(payload))
            val frame = incoming.receive() as Frame.Text
            val text = frame.readText()
            assertContains(text, "\"msg_type\":101")
        }
    }

    @Test
    fun `game websocket echoes msg_id on malformed ADD_RECORD payload`() = testApplication {
        environment {
            config = mapApplicationConfigForTests()
        }
        application {
            module()
        }

        val checksum = ByteArray(16) { it.toByte() }
        val checksumHex = checksum.toHex()
        val accessKey = ByteArray(16) { (it + 5).toByte() }
        val tokenHex = accessKey.toHex()

        transaction {
            PluginVersionsTable.insert {
                it[semver] = "1.0.0"
                it[checksumLinux] = checksum
                it[checksumWindows] = checksum
                it[isCutoff] = false
            }
            val keyBytes = accessKey
            GameServersTable.insert {
                it[name] = "ws-add-record-server"
                it[GameServersTable.accessKey] = keyBytes
            }
        }

        val json = Json { ignoreUnknownKeys = true }
        val helloEnvelope = WsEnvelope(
            msgType = MsgType.HELLO,
            msgId = 1L,
            data = json.encodeToJsonElement(
                HelloPayload(
                    pluginVersion = "1.0.0",
                    pluginChecksum = checksumHex,
                    mapName = "bkz_goldbhop",
                ),
            ),
        )
        val malformedAddRecord =
            """{"msg_type":8,"msg_id":5058,"data":{"steamid":"STEAM_0:0:43480797","time_ms":84191,"checkpoints":0,"gochecks":0,"local_uid":"00084191_0043480797_ba9dm67cl5y1"}}"""

        val client = createClient {
            install(WebSockets)
        }

        client.ws(
            path = "/ws/game",
            request = {
                header(HttpHeaders.Authorization, "Bearer $tokenHex")
            },
        ) {
            send(Frame.Text(json.encodeToString(WsEnvelope.serializer(), helloEnvelope)))
            incoming.receive() // HELLO_ACK

            send(Frame.Text(malformedAddRecord))
            val frame = incoming.receive() as Frame.Text
            val response = json.decodeFromString<WsEnvelope>(frame.readText())

            assertEquals(MsgType.ERROR, response.msgType)
            assertEquals(5058L, response.msgId)
            assertEquals(
                "Invalid message format",
                response.data.jsonObject["message"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `game websocket accepts binary too short for replay header`() = testApplication {
        environment {
            config = mapApplicationConfigForTests()
        }
        application {
            module()
        }

        val accessKey = ByteArray(16) { 9 }
        val tokenHex = accessKey.toHex()
        transaction {
            val keyBytes = accessKey
            GameServersTable.insert {
                it[name] = "ws-bin-server"
                it[GameServersTable.accessKey] = keyBytes
            }
        }

        val client = createClient {
            install(WebSockets)
        }

        client.ws(
            path = "/ws/game",
            request = {
                header(HttpHeaders.Authorization, "Bearer $tokenHex")
            },
        ) {
            send(Frame.Binary(true, ByteArray(10)))
        }
    }
}
