package com.kitsudo.kitmux.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Estado del receptor compartido entre [SyncService] y la UI (mismo proceso). */
object SyncStatus {
    data class State(
        val enabled: Boolean = false,
        /** Dirección en la que escucha, p. ej. "192.168.1.20:41235"; null si no hay WiFi. */
        val address: String? = null,
        val pairingCode: String? = null,
        val lastActivity: String? = null,
        /** Lo que está haciendo el PC ahora mismo (o hace menos de unos segundos). */
        val activity: Activity = Activity.IDLE,
    )

    enum class Activity { IDLE, RECEIVING, DELETING }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _libraryChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Emite cuando se han añadido, borrado o movido archivos (la biblioteca debe re-escanearse). */
    val libraryChanges: SharedFlow<Unit> = _libraryChanges.asSharedFlow()

    internal fun update(transform: (State) -> State) = _state.update(transform)

    internal fun notifyLibraryChanged() {
        _libraryChanges.tryEmit(Unit)
    }
}
