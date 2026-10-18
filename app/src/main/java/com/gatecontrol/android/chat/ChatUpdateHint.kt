package com.gatecontrol.android.chat

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "A newer app version supports all chat features": the messenger's
 * `features` (of `GET me`) the app knows. A key set to `true` that is not
 * in [KNOWN] belongs to a newer messenger than this app was built for.
 * Pure, unit-tested.
 */
object ChatFeatureHint {
    /** Every key of the `features` object this app knows (Messenger ≤ 1.4.0). */
    val KNOWN: Set<String> = setOf(
        "direct", "groups", "voice", "files", "location", "reactions", "edit", "search", "disappearing",
        "bridges", "e2e",
        ChatFeatures.VIDEO_META, ChatFeatures.FILE_THUMBS,
    ) + ChatFeatures14.ALL

    /** The keys the server switches on that this app does not know. */
    fun unknown(features: ChatFeatures?): Set<String> = features?.tokens.orEmpty() - KNOWN

    /** Identifies one set of unknown keys (a dismissal holds until another key turns up). */
    fun signature(unknown: Set<String>): String? = unknown.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")

    /** Whether the hint shows: unknown keys, and not this very set dismissed. */
    fun show(features: ChatFeatures?, dismissed: String?): Boolean {
        val sig = signature(unknown(features)) ?: return false
        return sig != dismissed
    }
}

/** Which set of unknown messenger features the person dismissed the update hint for (this device). */
@Singleton
class ChatUpdateHint @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _dismissed = MutableStateFlow(prefs.getString(KEY_DISMISSED, null))
    val dismissed: StateFlow<String?> = _dismissed.asStateFlow()

    fun dismiss(features: ChatFeatures?) {
        val sig = ChatFeatureHint.signature(ChatFeatureHint.unknown(features)) ?: return
        prefs.edit { putString(KEY_DISMISSED, sig) }
        _dismissed.value = sig
    }

    private companion object {
        const val PREFS = "chat_update_hint"
        const val KEY_DISMISSED = "dismissed"
    }
}
