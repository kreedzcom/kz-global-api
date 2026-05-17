package kz.global.api.domain.players

import kz.global.api.db.tables.PlayersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

class PlayerBanService {

    suspend fun isBanned(steamid: String): Boolean = suspendTransaction {
        PlayersTable
            .selectAll()
            .where { PlayersTable.steamid eq steamid }
            .singleOrNull()
            ?.get(PlayersTable.isBanned)
            ?: false
    }

    suspend fun setBanned(steamid: String, banned: Boolean) {
        suspendTransaction {
            PlayersTable.update({ PlayersTable.steamid eq steamid }) {
                it[isBanned] = banned
            }
        }
    }

}
