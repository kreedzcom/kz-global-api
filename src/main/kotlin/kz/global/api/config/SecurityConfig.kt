package kz.global.api.config

import io.ktor.server.config.*

data class SecurityConfig(
    /** When set, GET /metrics requires `Authorization: Bearer <key>`. */
    val metricsBearerKey: String?,
    val maxWsFrameBytes: Long,
    val maxReplayBytes: Long,
    val maxConcurrentReplayUploadsPerServer: Int,
    val replayUploadTtlMinutes: Long,
    val requireReplayForLeaderboard: Boolean,
    val wsUpgradePerIpPerMinute: Int,
    val addRecordPerServerPerMinute: Int,
    val replayBytesPerServerPerSecond: Long,
    val readQueryPerServerPerSecond: Int,
    val wantPlayerRecordsDefaultLimit: Int,
    val wantPlayerRecordsMaxLimit: Int,
    val eventLogRetentionDays: Long,
    val maxWrImprovementRatio: Double?,
)

fun ApplicationConfig.loadSecurityConfig(): SecurityConfig = SecurityConfig(
    metricsBearerKey = propertyOrNull("security.metricsBearerKey")?.getString()?.takeIf { it.isNotBlank() },
    maxWsFrameBytes = longProp("security.maxWsFrameBytes", 2L * 1024 * 1024),
    maxReplayBytes = longProp("security.maxReplayBytes", 100L * 1024 * 1024),
    maxConcurrentReplayUploadsPerServer = intProp("security.maxConcurrentReplayUploadsPerServer", 10),
    replayUploadTtlMinutes = longProp("security.replayUploadTtlMinutes", 30),
    requireReplayForLeaderboard = booleanProp("security.requireReplayForLeaderboard", false),
    wsUpgradePerIpPerMinute = intProp("security.wsUpgradePerIpPerMinute", 30),
    addRecordPerServerPerMinute = intProp("security.addRecordPerServerPerMinute", 120),
    replayBytesPerServerPerSecond = longProp("security.replayBytesPerServerPerSecond", 5L * 1024 * 1024),
    readQueryPerServerPerSecond = intProp("security.readQueryPerServerPerSecond", 20),
    wantPlayerRecordsDefaultLimit = intProp("security.wantPlayerRecordsDefaultLimit", 50),
    wantPlayerRecordsMaxLimit = intProp("security.wantPlayerRecordsMaxLimit", 100),
    eventLogRetentionDays = longProp("security.eventLogRetentionDays", 90),
    maxWrImprovementRatio = propertyOrNull("security.maxWrImprovementRatio")?.getString()?.toDoubleOrNull(),
)

private fun ApplicationConfig.longProp(path: String, default: Long): Long =
    propertyOrNull(path)?.getString()?.toLongOrNull() ?: default

private fun ApplicationConfig.intProp(path: String, default: Int): Int =
    propertyOrNull(path)?.getString()?.toIntOrNull() ?: default

private fun ApplicationConfig.booleanProp(path: String, default: Boolean): Boolean =
    propertyOrNull(path)?.getString()?.toBooleanStrictOrNull() ?: default
