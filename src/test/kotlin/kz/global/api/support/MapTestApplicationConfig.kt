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
        put("security.metricsBearerKey", "test-metrics-key")
        put("security.maxWsFrameBytes", "2097152")
        put("security.maxReplayBytes", "104857600")
        put("security.maxConcurrentReplayUploadsPerServer", "10")
        put("security.replayUploadTtlMinutes", "30")
        put("security.requireReplayForLeaderboard", "false")
        put("security.wsUpgradePerIpPerMinute", "1000")
        put("security.addRecordPerServerPerMinute", "1000")
        put("security.replayBytesPerServerPerSecond", "52428800")
        put("security.readQueryPerServerPerSecond", "1000")
        put("security.wantPlayerRecordsDefaultLimit", "50")
        put("security.wantPlayerRecordsMaxLimit", "100")
        put("security.eventLogRetentionDays", "90")
    }
