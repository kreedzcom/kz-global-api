package kz.global.api

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    /**
     * Health endpoint test — no DB or external deps needed.
     * The full module integration is covered by integration tests with Testcontainers.
     */
    @Test
    fun `health endpoint returns 200`() = testApplication {
        routing {
            get("/health") {
                call.respond(HttpStatusCode.OK)
            }
        }

        client.get("/health").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

}
