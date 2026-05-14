package kz.global.api.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kz.global.api.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory

class DatabaseFactory(private val config: DatabaseConfig) {

    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun connect() {
        log.info("Connecting to database...")
        val dataSource = buildDataSource()
        runMigrations(dataSource)
        Database.connect(dataSource)
        log.info("Database connected.")
    }

    private fun buildDataSource(): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            maximumPoolSize = config.maximumPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            if (config.username != null) {
                username = config.username
                password = config.password ?: ""
            }
            validate()
        }
        return HikariDataSource(hikariConfig)
    }

    private fun runMigrations(dataSource: HikariDataSource) {
        log.info("Running Flyway migrations...")
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        log.info("Migrations complete.")
    }

}
