package kz.global.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kz.global.api.api.*
import kz.global.api.auth.configureAdminAuth
import kz.global.api.config.loadAppConfig
import kz.global.api.db.DatabaseFactory
import kz.global.api.di.appModule
import kz.global.api.di.handlersModule
import kz.global.api.domain.replays.ReplayService
import kz.global.api.metrics.KzMetrics
import kz.global.api.ws.ConnectedServersRegistry
import kz.global.api.ws.gameServerWsRoute
import org.koin.core.qualifier.named
import org.koin.ktor.ext.*
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val log = LoggerFactory.getLogger("Application")
    val config = loadAppConfig()

    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    install(MicrometerMetrics) {
        registry = prometheusRegistry
    }

    install(Koin) {
        modules(appModule(config, prometheusRegistry), handlersModule())
    }

    val db by inject<DatabaseFactory>()
    db.connect()

    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
    }

    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled exception: {}", cause.message, cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    install(Authentication) {
        configureAdminAuth(config.admin)
    }

    val registry by inject<ConnectedServersRegistry>()
    monitor.subscribe(ApplicationStopPreparing) {
        log.info("Shutting down — closing all WebSocket sessions...")
        runBlocking { registry.closeAll() }
        runCatching { getKoin().get<CoroutineScope>(named("applicationCoroutineScope")).cancel() }
            .onFailure { e -> log.warn("Failed to cancel application scope: {}", e.message) }
    }

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK)
        }
        get("/metrics") {
            call.respond(prometheusRegistry.scrape())
        }
        gameServerWsRoute()
        serversRoute()
        pluginVersionsRoute()
        recordsRoute()
        mapTimesRoute()
    }
}
