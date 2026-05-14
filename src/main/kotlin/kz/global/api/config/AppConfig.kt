package kz.global.api.config

import io.ktor.server.application.*

data class DatabaseConfig(
    val url: String,
    val maximumPoolSize: Int = 10,
    /** When set (e.g. H2 in tests), passed to Hikari. Postgres URLs often embed credentials in [url] instead. */
    val username: String? = null,
    val password: String? = null,
)

data class R2Config(
    val endpoint: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val bucket: String,
)

data class AdminConfig(
    val bearerKey: String,
)

data class AppConfig(
    val database: DatabaseConfig,
    val r2: R2Config,
    val admin: AdminConfig,
)

fun Application.loadAppConfig(): AppConfig = AppConfig(
    database = DatabaseConfig(
        url = environment.config.property("database.url").getString(),
        maximumPoolSize = environment.config.propertyOrNull("database.maximumPoolSize")
            ?.getString()?.toInt() ?: 10,
        username = environment.config.propertyOrNull("database.username")?.getString(),
        password = environment.config.propertyOrNull("database.password")?.getString(),
    ),
    r2 = R2Config(
        endpoint = environment.config.property("r2.endpoint").getString(),
        accessKeyId = environment.config.property("r2.accessKeyId").getString(),
        secretAccessKey = environment.config.property("r2.secretAccessKey").getString(),
        bucket = environment.config.property("r2.bucket").getString(),
    ),
    admin = AdminConfig(
        bearerKey = environment.config.property("admin.bearerKey").getString(),
    )
)
