package kz.global.api

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    @Test
    fun `health endpoint returns 200`() = testApplication {
        application { module() }

        client.get("/health").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
