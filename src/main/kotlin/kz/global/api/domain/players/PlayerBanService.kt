package kz.global.api.domain.players

import kz.global.api.db.tables.PlayersTable
import kz.global.api.events.AuditLogger
import kz.global.api.security.AdminActor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

sealed class PlayerBanResult {
    data object Success : PlayerBanResult()
    data object NotFound : PlayerBanResult()
}

class PlayerBanService(
    private val auditLogger: AuditLogger,
) {

    suspend fun isBanned(steamid: String): Boolean = suspendTransaction {
        PlayersTable
            .selectAll()
            .where { PlayersTable.steamid eq steamid }
            .singleOrNull()
            ?.get(PlayersTable.isBanned)
            ?: false
    }

    suspend fun setBanned(steamid: String, banned: Boolean, actor: String? = null): PlayerBanResult {
        val updated = suspendTransaction {
            val exists = PlayersTable
                .selectAll()
                .where { PlayersTable.steamid eq steamid }
                .count() > 0
            if (!exists) return@suspendTransaction false

            PlayersTable.update({ PlayersTable.steamid eq steamid }) {
                it[isBanned] = banned
            }
            true
        }

        if (!updated) return PlayerBanResult.NotFound

        val payload = AdminActor.withActor(
            buildJsonObject {
                put("steamid", steamid)
            },
            actor,
        )
        auditLogger.log(
            if (banned) "PLAYER_BANNED" else "PLAYER_UNBANNED",
            null,
            payload,
        )
        return PlayerBanResult.Success
    }

}
