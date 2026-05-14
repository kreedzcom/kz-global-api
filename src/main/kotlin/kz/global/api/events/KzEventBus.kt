package kz.global.api.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.uuid.Uuid

sealed class KzEvent {
    data class NewRecord(
        val recordId: Uuid,
        val playerSteamid: String,
        val mapName: String,
        val timeMs: Long,
        val teleports: Int,
    ) : KzEvent()

    data class NewWorldRecord(
        val recordId: Uuid,
        val playerSteamid: String,
        val mapName: String,
        val timeMs: Long,
        val category: String,
    ) : KzEvent()
}

class KzEventBus {
    private val _events = MutableSharedFlow<KzEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<KzEvent> = _events.asSharedFlow()

    suspend fun emit(event: KzEvent) {
        _events.emit(event)
    }
}
