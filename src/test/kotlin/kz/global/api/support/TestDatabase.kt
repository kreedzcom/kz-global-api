package kz.global.api.support

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val H2_URL =
    "jdbc:h2:mem:kz_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;" +
    "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DEFAULT_NULL_ORDERING=HIGH"

/**
 * Shared in-memory H2 database (PostgreSQL compatibility mode) for integration tests.
 *
 * Flyway migrations run once when [connect] is first called. Each test should call
 * [truncateAll] in @BeforeEach for full isolation.
 *
 * H2's PostgreSQL mode understands BYTEA, TIMESTAMPTZ, SERIAL, UUID,
 * and ON CONFLICT DO UPDATE — so the Flyway migration SQL runs as-is.
 */
object TestDatabase {

    val database: Database by lazy {
        Flyway.configure()
            .dataSource(H2_URL, "sa", "")
            .locations("classpath:db/migration")
            .load()
            .migrate()

        Database.connect(url = H2_URL, driver = "org.h2.Driver", user = "sa", password = "")
    }

    /** Force lazy initialization — call once in @BeforeAll. */
    fun connect() { database }

    /**
     * Wipes all application tables between tests in FK-safe dependency order.
     * Uses DELETE FROM (compatible with both H2 and PostgreSQL).
     */
    fun truncateAll() {
        transaction(database) {
            listOf(
                "world_record", "best_nub_record", "best_pro_record",
                "map_record", "map_minimum_time", "replay_upload_session",
                "event_log", "plugin_version", "game_server", "player", "map",
            ).forEach { table -> exec("DELETE FROM $table") }
        }
    }
}
