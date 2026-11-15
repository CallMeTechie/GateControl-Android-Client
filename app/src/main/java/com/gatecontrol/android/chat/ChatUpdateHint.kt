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
    /**
     * Every key of the `features` object this app can handle (Messenger ≤ 1.20.0). `bridges` and `e2e` are words the
     * messenger names but keeps `false`: the app has nothing for them, so they are not here — switched on one day,
     * they show the hint like any other unknown word.
     */
    val KNOWN: Set<String> = setOf(
        "direct", "groups", "voice", "files", "location", "reactions", "edit", "search", "disappearing",
        ChatFeatures.VIDEO_META, ChatFeatures.FILE_THUMBS,
        // 1.5.0: Freigaben anfragen (approvers), „Am Ende ausschalten“
        "action_requests", "grant_off",
        // 1.6.0: „Wo Mitteilungen erscheinen“ (NotifyRoutesScreen)
        "notice_routes",
        // 1.11.0: Virenscan an Dateien (`media.scan`, MediaScan)
        "scan",
        // 1.13.0: „Verknüpfte Dienste“ (Chat-Einstellungen, ChatLinkedServicesPage)
        ChatBridges.FEATURE,
        // 1.14.0: Chats über eine Brücke (`conversation.bridge`, `?bridged=1`)
        ChatBridgeChat.FEATURE,
        // 1.15.0: Dateien und Profilbilder über eine Brücke, neuer Chat per Nummer („WhatsApp-Chat mit Nummer“)
        ChatBridgeChat.FEATURE_MEDIA,
        ChatBridgeChat.FEATURE_NEW_CHAT,
        // 1.17.0: älterer Verlauf über eine Brücke (Wahl in „Verknüpfte Dienste“, Dateien auf Abruf, Handy-Hinweis)
        ChatBridgeChat.FEATURE_HISTORY,
        // 1.18.0: „Neuer Chat“ mit Kontakten (GateControl, WhatsApp, Adressbuch; NewChatScreen)
        ChatBridgeChat.FEATURE_CONTACTS,
        // 1.20.0: Kanäle auf eigener Seite (`GET channels`, `?channels=1`), Seitenmenü und TV-Symbol
        ChatBridgeChat.FEATURE_CHANNELS,
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
