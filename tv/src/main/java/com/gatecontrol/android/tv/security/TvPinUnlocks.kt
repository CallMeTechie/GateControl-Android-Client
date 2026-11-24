package com.gatecontrol.android.tv.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The functions opened with the TV PIN that stay open for a while ([TvPinFunction.holds], [TvPinHold]): Notizen now,
 * later the messages. Only in memory: a restart, the photo frame or the screen saver lock them again.
 */
@Singleton
class TvPinUnlocks @Inject constructor() {

    /** Replaceable in tests. */
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    private val _until = MutableStateFlow<Map<TvPinFunction, Long>>(emptyMap())

    /** When each open function closes (elapsed realtime). */
    val until: StateFlow<Map<TvPinFunction, Long>> = _until.asStateFlow()

    fun isOpen(function: TvPinFunction): Boolean = TvPinHold.isOpen(_until.value, function, clock())

    /** The PIN was entered for [function]. */
    fun opened(function: TvPinFunction) = _until.update { TvPinHold.open(it, function, clock()) }

    /** A key of the remote: what is open stays open. */
    fun touch() {
        if (_until.value.isEmpty()) return
        _until.update { TvPinHold.touch(it, clock()) }
    }

    /** The photo frame or the screen saver came, GateControl left the front, the PIN changed: all closed. */
    fun lockAll() {
        _until.value = emptyMap()
    }
}
