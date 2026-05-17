package kz.global.api.di

import kz.global.api.ws.handlers.*
import org.koin.dsl.module

fun handlersModule() = module {
    single { HelloHandler(get(), get()) }
    single { MapChangeHandler(get()) }
    single { PlayerJoinHandler(get()) }
    single { PlayerLeaveHandler() }
    single { MapInfoHandler(get()) }
    single { CourseTopHandler(get()) }
    single { PlayerRecordsHandler(get(), get()) }
    single { AddRecordHandler(get(), get()) }
    single { ReplayChunkHandler(get(), get(), get(), get()) }
}
