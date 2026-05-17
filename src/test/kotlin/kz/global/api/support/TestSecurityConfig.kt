package kz.global.api.support

import kz.global.api.config.SecurityConfig

fun testSecurityConfig(
    requireReplayForLeaderboard: Boolean = false,
    maxReplayBytes: Long = 104_857_600L,
    maxConcurrentReplayUploadsPerServer: Int = 10,
    maxWrImprovementRatio: Double? = null,
    addRecordPerServerPerMinute: Int = 1000,
    readQueryPerServerPerSecond: Int = 1000,
    eventLogRetentionDays: Long = 90,
) = SecurityConfig(
    metricsBearerKey = "test-metrics-key",
    maxWsFrameBytes = 2L * 1024 * 1024,
    maxReplayBytes = maxReplayBytes,
    maxConcurrentReplayUploadsPerServer = maxConcurrentReplayUploadsPerServer,
    replayUploadTtlMinutes = 30,
    requireReplayForLeaderboard = requireReplayForLeaderboard,
    wsUpgradePerIpPerMinute = 1000,
    addRecordPerServerPerMinute = addRecordPerServerPerMinute,
    replayBytesPerServerPerSecond = 52_428_800L,
    readQueryPerServerPerSecond = readQueryPerServerPerSecond,
    wantPlayerRecordsDefaultLimit = 50,
    wantPlayerRecordsMaxLimit = 100,
    eventLogRetentionDays = eventLogRetentionDays,
    maxWrImprovementRatio = maxWrImprovementRatio,
)

fun testWsRateLimiters() = kz.global.api.security.WsRateLimiters(testSecurityConfig())

fun testWsRateLimitersStrict() = kz.global.api.security.WsRateLimiters(
    testSecurityConfig(addRecordPerServerPerMinute = 1, readQueryPerServerPerSecond = 1),
)
