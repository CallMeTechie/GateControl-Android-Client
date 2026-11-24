package com.gatecontrol.android.tv.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TV PIN on the device: only its hash ([TvPinHash]), the functions that ask for it and the failure count, in the
 * app's private preferences (never in a backup: `allowBackup` is off). Checking and hashing run off the main thread.
 */
@Singleton
class TvPinStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    private val _state = MutableStateFlow(read())
    val state: StateFlow<TvPinState> = _state.asStateFlow()

    fun needsPin(function: TvPinFunction): Boolean = _state.value.needsPin(function)

    suspend fun check(pin: String): TvPinCheck = withContext(Dispatchers.Default) {
        synchronized(this@TvPinStore) {
            val (next, result) = TvPinRules.check(_state.value, pin, clock())
            write(next)
            result
        }
    }

    /** Sets or changes the PIN (the caller checked the old one first). */
    suspend fun setPin(pin: String) {
        val hash = withContext(Dispatchers.Default) { TvPinHash.hash(pin) }
        synchronized(this) { write(TvPinRules.setPin(_state.value, hash)) }
    }

    fun removePin() = synchronized(this) { write(TvPinRules.removePin(_state.value)) }

    fun setRequired(function: TvPinFunction, on: Boolean) = synchronized(this) {
        write(TvPinRules.setRequired(_state.value, function, on))
    }

    /** The offer to set a PIN for [function] was shown. */
    fun offered(function: TvPinFunction) = synchronized(this) { write(TvPinRules.offered(_state.value, function)) }

    private fun read(): TvPinState = TvPinState(
        hash = prefs.getString(KEY_HASH, null)?.takeIf { it.isNotBlank() },
        required = prefs.getStringSet(KEY_REQUIRED, emptySet()).orEmpty().mapNotNull(TvPinFunction::fromWire).toSet(),
        failures = prefs.getInt(KEY_FAILURES, 0).coerceAtLeast(0),
        lastFailureAt = prefs.getLong(KEY_LAST_FAILURE, 0L),
        off = prefs.getStringSet(KEY_OFF, emptySet()).orEmpty().mapNotNull(TvPinFunction::fromWire).toSet(),
        offered = prefs.getStringSet(KEY_OFFERED, emptySet()).orEmpty().mapNotNull(TvPinFunction::fromWire).toSet(),
    )

    private fun write(state: TvPinState) {
        prefs.edit {
            if (state.hash == null) remove(KEY_HASH) else putString(KEY_HASH, state.hash)
            putStringSet(KEY_REQUIRED, state.required.map { it.wire }.toSet())
            putInt(KEY_FAILURES, state.failures)
            putLong(KEY_LAST_FAILURE, state.lastFailureAt)
            putStringSet(KEY_OFF, state.off.map { it.wire }.toSet())
            putStringSet(KEY_OFFERED, state.offered.map { it.wire }.toSet())
        }
        _state.value = state
    }

    private companion object {
        const val FILE = "gatecontrol_tv_pin"
        const val KEY_HASH = "hash"
        const val KEY_REQUIRED = "required"
        const val KEY_FAILURES = "failures"
        const val KEY_LAST_FAILURE = "last_failure"
        const val KEY_OFF = "off"
        const val KEY_OFFERED = "offered"
    }
}
