package kz.global.api.support

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Shared in-memory H2 URL (PostgreSQL compatibility mode).
 * Must match `database.url` in `application-test.conf` so full [kz.global.api.module] tests share the same DB as [TestDatabase].
 */
const val TEST_JDBC_URL =
    "jdbc:h2:mem:kz_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;" +
        "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DEFAULT_NULL_ORDERING=HIGH"

object TestDatabase {

    val database: Database by lazy {
        Flyway.configure()
            .dataSource(TEST_JDBC_URL, "sa", "")
            .locations("classpath:db/migration")
            .load()
            .migrate()

        Database.connect(url = TEST_JDBC_URL, driver = "org.h2.Driver", user = "sa", password = "")
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
