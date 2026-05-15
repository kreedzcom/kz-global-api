package kz.global.api.di

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kz.global.api.config.AppConfig
import kz.global.api.db.DatabaseFactory
import kz.global.api.domain.broadcast.BroadcastService
import kz.global.api.domain.records.RecordService
import kz.global.api.domain.replays.ReplayService
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

fun appModule(config: AppConfig, prometheusRegistry: PrometheusMeterRegistry) = module {
    single { config }
    single { prometheusRegistry }
    single<MeterRegistry> { get<PrometheusMeterRegistry>() }
    single { DatabaseFactory(config.database) }
    single { R2Client(config.r2) }
    single { KzEventBus() }
    single { AuditLogger() }
    single { ConnectedServersRegistry() }
    single(named("applicationCoroutineScope")) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    single { KzMetrics(get(), get()) }
    single { RecordService(get(), get(), get()) }
    single { ReplayService(get(), get()) }
    single { BroadcastService(get(), get(), Dispatchers.IO, get(named("applicationCoroutineScope"))) }
}
