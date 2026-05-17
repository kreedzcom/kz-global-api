package kz.global.api.security

import kz.global.api.config.SecurityConfig
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WsRateLimiters(config: SecurityConfig) {

    val wsUpgradeByIp = FixedWindowRateLimiter(1.minutes, config.wsUpgradePerIpPerMinute)

    val addRecordByServer = FixedWindowRateLimiter(1.minutes, config.addRecordPerServerPerMinute)

    val readQueryByServer = FixedWindowRateLimiter(1.seconds, config.readQueryPerServerPerSecond)

    val replayBytesByServer = ByteBudgetRateLimiter(1.seconds, config.replayBytesPerServerPerSecond)

}
