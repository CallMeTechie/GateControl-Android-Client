package com.gatecontrol.android.places

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What the push stream says about „Meine Orte“, for whoever keeps a copy (the profile's list, the choice, the geofences
 * of Familie and Notizen). A plain hub so the server-control handler needs no dependency on them.
 */
object PlacesSignals {
    /** A change of places of the owner ([ids], [deleted]); a reconnect of the stream: [ids] empty, [reconnect] true. */
    data class Change(val ids: List<Long>, val deleted: Boolean, val reconnect: Boolean = false)

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    private val _reloaded = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** The own places were loaded again and differ from before: the geofences that use them look again. */
    val reloaded: SharedFlow<Unit> = _reloaded.asSharedFlow()

    fun changed(ids: List<Long>, deleted: Boolean) {
        _changes.tryEmit(Change(ids, deleted))
    }

    fun reconnected() {
        _changes.tryEmit(Change(emptyList(), deleted = false, reconnect = true))
    }

    fun notifyReloaded() {
        _reloaded.tryEmit(Unit)
    }
}
