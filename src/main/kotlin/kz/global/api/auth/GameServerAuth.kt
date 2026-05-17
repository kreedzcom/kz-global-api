package kz.global.api.auth

import kz.global.api.db.tables.GameServersTable
import kz.global.api.security.IpAllowlist
import kz.global.api.util.fromHex
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

suspend fun resolveGameServerToken(bearerToken: String, clientIp: String): Int? {

    val keyBytes = runCatching { bearerToken.fromHex() }.getOrNull()
        ?: return null
    if (keyBytes.size != 16) return null

    return suspendTransaction {
        val row = GameServersTable
            .selectAll()
            .where { (GameServersTable.accessKey eq keyBytes) and (GameServersTable.active eq true) }
            .singleOrNull()
            ?: return@suspendTransaction null

        if (!IpAllowlist.isAllowed(clientIp, row[GameServersTable.allowedIps])) {
            return@suspendTransaction null
        }

        val serverId = row[GameServersTable.id]

        GameServersTable.update({ GameServersTable.id eq serverId }) {
            it[lastConnectedAt] = Clock.System.now()
        }

        serverId
    }

}
