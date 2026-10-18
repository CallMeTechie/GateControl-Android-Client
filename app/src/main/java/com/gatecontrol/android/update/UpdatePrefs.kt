package com.gatecontrol.android.update

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local state of the Play in-app update (plain SharedPreferences). */
@Singleton
class UpdatePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, true))
    /** "Nur über WLAN laden" (default on). */
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    fun setWifiOnly(on: Boolean) {
        prefs.edit { putBoolean(KEY_WIFI_ONLY, on) }
        _wifiOnly.value = on
    }

    private val _waitingCode = MutableStateFlow(prefs.getInt(KEY_WAITING, 0))
    /** Version code that waits for an unmetered network (0 = none). */
    val waitingCode: StateFlow<Int> = _waitingCode.asStateFlow()

    fun setWaiting(code: Int) {
        prefs.edit { putInt(KEY_WAITING, code) }
        _waitingCode.value = code
    }

    private val _snooze = MutableStateFlow(prefs.getInt(KEY_SNOOZE_CODE, 0) to prefs.getLong(KEY_SNOOZE_UNTIL, 0))
    /** Version code and end time of "Später erinnern". */
    val snooze: StateFlow<Pair<Int, Long>> = _snooze.asStateFlow()

    fun snooze(code: Int, untilMs: Long) {
        prefs.edit { putInt(KEY_SNOOZE_CODE, code); putLong(KEY_SNOOZE_UNTIL, untilMs) }
        _snooze.value = code to untilMs
    }

    /** "Später – installiert sich, wenn die App im Hintergrund ist" was chosen. */
    var installInBackground: Boolean
        get() = prefs.getBoolean(KEY_INSTALL_BG, false)
        set(value) = prefs.edit { putBoolean(KEY_INSTALL_BG, value) }

    var marker: PostUpdateMarker?
        get() = PostUpdateMarker.decode(prefs.getString(KEY_MARKER, null))
        // commit: the process is about to be replaced by the installer.
        set(value) = prefs.edit(commit = true) { if (value == null) remove(KEY_MARKER) else putString(KEY_MARKER, value.encode()) }

    private val _lastChecked = MutableStateFlow(prefs.getLong(KEY_LAST_CHECKED, 0))
    /** Time of the last successful check (wall clock, 0 = never). */
    val lastChecked: StateFlow<Long> = _lastChecked.asStateFlow()

    fun setLastChecked(ms: Long) {
        prefs.edit { putLong(KEY_LAST_CHECKED, ms) }
        _lastChecked.value = ms
    }

    /** Cached release notes for the step [fromCode] → [toCode] ("kind|text" per line). */
    fun notes(fromCode: Int, toCode: Int): List<ReleaseNote>? =
        prefs.getString(notesKey(fromCode, toCode), null)?.let(::decodeNotes)

    fun setNotes(fromCode: Int, toCode: Int, notes: List<ReleaseNote>) {
        prefs.edit {
            // Only the latest step is kept.
            prefs.all.keys.filter { it.startsWith(NOTES_PREFIX) }.forEach { remove(it) }
            putString(notesKey(fromCode, toCode), encodeNotes(notes))
        }
    }

    private fun notesKey(from: Int, to: Int) = "$NOTES_PREFIX${from}_$to"

    internal companion object {
        const val PREFS = "play_update"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_WAITING = "waiting_code"
        const val KEY_SNOOZE_CODE = "snooze_code"
        const val KEY_SNOOZE_UNTIL = "snooze_until"
        const val KEY_INSTALL_BG = "install_in_background"
        const val KEY_MARKER = "post_update_marker"
        const val KEY_LAST_CHECKED = "last_checked"
        const val NOTES_PREFIX = "notes_"

        fun encodeNotes(notes: List<ReleaseNote>): String =
            notes.joinToString("\n") { "${it.kind.name}|${it.text.replace('\n', ' ')}" }

        fun decodeNotes(raw: String): List<ReleaseNote> = raw.lineSequence().mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) return@mapNotNull null
            val kind = ReleaseNote.Kind.entries.firstOrNull { it.name == line.substring(0, i) } ?: ReleaseNote.Kind.Other
            line.substring(i + 1).takeIf { it.isNotBlank() }?.let { ReleaseNote(it, kind) }
        }.toList()
    }
}
