package com.gatecontrol.android.terminal

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The terminal's own settings on this phone (for every device, design „Die Größe gilt je App für alle Geräte“). */
data class TermSettings(
    val fontSp: Int = TermPrefs.FONT_DEFAULT,
    val extraKeys: Boolean = true,
    val askPaste: Boolean = true,
    /** „Bildschirmfotos im Terminal verbieten“: FLAG_SECURE on the terminal (off by default). */
    val blockScreenshots: Boolean = false,
)

@Singleton
class TermPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<TermSettings> = _state.asStateFlow()

    private fun read() = TermSettings(
        fontSp = prefs.getInt(KEY_FONT, FONT_DEFAULT).coerceIn(FONT_MIN, FONT_MAX),
        extraKeys = prefs.getBoolean(KEY_KEYS, true),
        askPaste = prefs.getBoolean(KEY_PASTE, true),
        blockScreenshots = prefs.getBoolean(KEY_SECURE, false),
    )

    private fun update(change: (TermSettings) -> TermSettings) {
        val next = change(_state.value)
        prefs.edit {
            putInt(KEY_FONT, next.fontSp)
            putBoolean(KEY_KEYS, next.extraKeys)
            putBoolean(KEY_PASTE, next.askPaste)
            putBoolean(KEY_SECURE, next.blockScreenshots)
        }
        _state.value = next
    }

    fun setFont(sp: Int) = update { it.copy(fontSp = sp.coerceIn(FONT_MIN, FONT_MAX)) }
    fun setExtraKeys(on: Boolean) = update { it.copy(extraKeys = on) }
    fun setAskPaste(on: Boolean) = update { it.copy(askPaste = on) }
    fun setBlockScreenshots(on: Boolean) = update { it.copy(blockScreenshots = on) }

    companion object {
        const val FONT_MIN = 8
        const val FONT_MAX = 24
        const val FONT_DEFAULT = 14
        private const val PREFS = "terminal"
        private const val KEY_FONT = "font_sp"
        private const val KEY_KEYS = "extra_keys"
        private const val KEY_PASTE = "ask_paste"
        private const val KEY_SECURE = "block_screenshots"
    }
}
