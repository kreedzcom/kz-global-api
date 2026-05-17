package kz.global.api.config

import io.ktor.server.testing.testApplication
import kz.global.api.config.loadAppConfig
import kz.global.api.support.TEST_JDBC_URL
import kz.global.api.support.mapApplicationConfigForTests
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigLoadTest {

    @Test
    fun `loadAppConfig uses default maximumPoolSize when omitted`() = testApplication {
        environment {
            config = MapApplicationConfig().apply {
                put("database.url", TEST_JDBC_URL)
                put("database.username", "sa")
                put("database.password", "")
                put("r2.endpoint", "https://test.r2.example.com")
                put("r2.accessKeyId", "k")
                put("r2.secretAccessKey", "s")
                put("r2.bucket", "b")
                put("admin.bearerKey", "admin-key")
                put("security.metricsBearerKey", "metrics-key")
            }
        }

        application {
            val c = loadAppConfig()

            assertEquals(10, c.database.maximumPoolSize)
            assertEquals("metrics-key", c.security.metricsBearerKey)
        }
    }

    @Test
    fun `loadAppConfig reads explicit maximumPoolSize`() = testApplication {
        environment {
            config = MapApplicationConfig().apply {
                put("database.url", TEST_JDBC_URL)
                put("database.maximumPoolSize", "3")
                put("database.username", "sa")
                put("database.password", "")
                put("r2.endpoint", "https://test.r2.example.com")
                put("r2.accessKeyId", "k")
                put("r2.secretAccessKey", "s")
                put("r2.bucket", "b")
                put("admin.bearerKey", "admin-key")
                put("security.metricsBearerKey", "metrics-key")
            }
        }

        application {
            val c = loadAppConfig()

            assertEquals(3, c.database.maximumPoolSize)
        }
    }

    @Test
    fun `mapApplicationConfigForTests matches application-test conf pool size`() = testApplication {
        environment {
            config = mapApplicationConfigForTests()
        }

        application {
            val c = loadAppConfig()

            assertEquals(2, c.database.maximumPoolSize)
        }
    }
}
