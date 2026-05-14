package kz.global.api.config

import io.ktor.server.application.*

data class DatabaseConfig(
    val url: String,
    val maximumPoolSize: Int = 10,
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
