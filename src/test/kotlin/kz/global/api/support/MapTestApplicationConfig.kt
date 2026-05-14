package kz.global.api.support

import io.ktor.server.config.MapApplicationConfig

/**
 * HOCON-like flat keys for [io.ktor.server.application.Application.loadAppConfig] in tests.
 * Uses the same JDBC URL as [TestDatabase] so Flyway + Exposed share one in-memory DB with [kz.global.api.module].
 */
fun mapApplicationConfigForTests(includeMaximumPoolSize: Boolean = true): MapApplicationConfig =
    MapApplicationConfig().apply {
        put("database.url", TEST_JDBC_URL)
        if (includeMaximumPoolSize) {
            put("database.maximumPoolSize", "2")
        }
        put("database.username", "sa")
        put("database.password", "")
        put("r2.endpoint", "https://test.r2.example.com")
        put("r2.accessKeyId", "test-key-id")
        put("r2.secretAccessKey", "test-secret")
        put("r2.bucket", "test-bucket")
        put("admin.bearerKey", "test-admin-bearer-key")
    }
