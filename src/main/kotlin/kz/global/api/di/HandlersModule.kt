package kz.global.api.di

import kz.global.api.ws.handlers.*
import org.koin.dsl.module

fun handlersModule() = module {
    single { HelloHandler(get(), get()) }
    single { MapChangeHandler(get()) }
    single { PlayerJoinHandler() }
    single { PlayerLeaveHandler() }
    single { MapInfoHandler(get()) }
    single { CourseTopHandler() }
    single { PlayerRecordsHandler() }
    single { AddRecordHandler(get()) }
    single { ReplayChunkHandler(get(), get()) }
}
