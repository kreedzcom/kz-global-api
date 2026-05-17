package kz.global.api.support

import kz.global.api.config.SecurityConfig

fun testSecurityConfig(
    requireReplayForLeaderboard: Boolean = false,
    maxReplayBytes: Long = 104_857_600L,
    maxConcurrentReplayUploadsPerServer: Int = 10,
    maxWrImprovementRatio: Double? = null,
) = SecurityConfig(
    metricsBearerKey = "test-metrics-key",
    maxWsFrameBytes = 2L * 1024 * 1024,
    maxReplayBytes = maxReplayBytes,
    maxConcurrentReplayUploadsPerServer = maxConcurrentReplayUploadsPerServer,
    replayUploadTtlMinutes = 30,
    requireReplayForLeaderboard = requireReplayForLeaderboard,
    wsUpgradePerIpPerMinute = 1000,
    addRecordPerServerPerMinute = 1000,
    replayBytesPerServerPerSecond = 52_428_800L,
    readQueryPerServerPerSecond = 1000,
    wantPlayerRecordsDefaultLimit = 50,
    wantPlayerRecordsMaxLimit = 100,
    eventLogRetentionDays = 90,
    maxWrImprovementRatio = maxWrImprovementRatio,
)

fun testWsRateLimiters() = kz.global.api.security.WsRateLimiters(testSecurityConfig())
