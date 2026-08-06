package kz.global.api.support

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kz.global.api.api.*
import kz.global.api.auth.configureAdminAuth
import kz.global.api.config.AdminConfig
import kz.global.api.domain.broadcast.BroadcastService
import kz.global.api.domain.players.PlayerBanService
import kz.global.api.domain.records.LeaderboardRepairService
import kz.global.api.domain.records.RecordAdminService
import kz.global.api.domain.records.RecordService
import kz.global.api.domain.replays.ReplayService
import kz.global.api.support.testSecurityConfig
import kz.global.api.events.AuditLogger
import kz.global.api.events.KzEventBus
import kz.global.api.metrics.KzMetrics
import kz.global.api.storage.R2Client
import kz.global.api.ws.ConnectedServersRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import io.ktor.http.*

const val TEST_ADMIN_KEY = "test-admin-bearer-key"

/**
 * Configures a minimal Ktor test application for admin route tests.
 *
 * Does NOT call [DatabaseFactory.connect] — relies on the global Exposed
 * connection already established by [TestDatabase.connect] in @BeforeAll.
 */
fun ApplicationTestBuilder.setupAdminRoutes(
    adminKey: String = TEST_ADMIN_KEY,
    r2Client: R2Client = mockk(relaxed = true),
    configureRegistry: ConnectedServersRegistry.() -> Unit = {},
    broadcastService: BroadcastService? = null,
) {
    val r2Binding = r2Client
    val broadcastBinding = broadcastService
    application {
        val meterRegistry = SimpleMeterRegistry()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        install(ContentNegotiation) { json() }

        install(StatusPages) {
            exception<Throwable> { call, _ ->
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        install(Authentication) {
            configureAdminAuth(AdminConfig(bearerKey = adminKey))
        }

        install(Koin) {
            modules(module {
                single { ConnectedServersRegistry().also(configureRegistry) }
                single { meterRegistry as io.micrometer.core.instrument.MeterRegistry }
                single { KzMetrics(get(), get()) }
                single { KzEventBus() }
                single { AuditLogger() }
                single<R2Client> { r2Binding }
                single { testSecurityConfig() }
                single { PlayerBanService() }
                single { ReplayService(get(), get(), get()) }
                single { RecordService(get(), get(), get(), get(), get(), get()) }
                single { LeaderboardRepairService() }
                single(named("applicationCoroutineScope")) { testScope }
                single {
                    RecordAdminService(
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        broadcastBinding ?: BroadcastService(get(), get(), Dispatchers.Unconfined, testScope),
                        get(),
                        get(named("applicationCoroutineScope")),
                    )
                }
            })
        }

        routing {
            serversRoute()
            pluginVersionsRoute()
            recordsRoute()
            mapTimesRoute()
            playersRoute()
            mapsReplayRoute()
        }
    }
}

/** Returns a [HttpHeaders.Authorization] header value for the admin bearer key. */
fun adminAuth(key: String = TEST_ADMIN_KEY) = "Bearer $key"
