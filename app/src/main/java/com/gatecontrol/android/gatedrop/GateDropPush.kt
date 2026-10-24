package com.gatecontrol.android.gatedrop

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.widget.Toast
import com.gatecontrol.android.R
import com.gatecontrol.android.notify.PluginEventSink
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonElement
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GateDrop's pushes:
 * * a silent data push `{ gatedrop: { transfer, … } }` (direct transfer)
 *   is consumed — never in the inbox or the shade — and starts the
 *   receive worker;
 * * a delivery `{ gatedrop: { send } }` is shown as GateDrop's own
 *   notification ("<Absender> hat dir … geschickt", Öffnen / Speichern /
 *   Kopieren) and its content fetched (small files saved right away).
 * Only pushes of the GateDrop plugin's topics count.
 */
@Singleton
class GateDropPushHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: GateDropNotifier,
    private val store: GateDropStore,
    private val repository: dagger.Lazy<GateDropRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private fun ours(message: PushMessage): Boolean = message.topic.startsWith("plugin:$GATEDROP_PLUGIN:")

    /**
     * A transfer push (a data push, `kind: "data"`, core 1.193+; older
     * builds: any push with `gatedrop.transfer`): handled here completely
     * (true), nothing to show. A data push naming a send is fetched
     * quietly (small files saved, no notification of its own).
     */
    fun consume(message: PushMessage): Boolean {
        if (!ours(message)) return false
        val data = GateDropJson.pushData(message.dataJson) ?: return false
        if (data.has("transfer")) {
            val offer = GateDropJson.offer(data)
            if (offer != null) {
                GateDropReceiveWorker.enqueueOffer(context, offer, data.toString())
            } else {
                // Unusable: tell the plugin now, the sender must not wait for this device.
                GateDropJson.transferIdOf(data)?.let { id -> scope.launch { runCatching { repository.get().reportDirectFailed(id, "bad_offer") } } }
            }
            // Even a malformed transfer push is no notification of its own.
            return true
        }
        if (message.isData) {
            GateDropJson.noticeSendId(data)?.let { sendId ->
                GateDropReceiveWorker.enqueueSend(context, sendId, save = false, autoSave = true, show = false)
            }
            return true
        }
        return false
    }

    /** Send id of a GateDrop delivery push; null for anything else. */
    fun deliverySendId(message: PushMessage): String? {
        if (!ours(message)) return null
        return GateDropJson.noticeSendId(GateDropJson.pushData(message.dataJson))
    }

    /** Shows a delivery push as GateDrop's notification (what is known so far); true when shown. */
    suspend fun show(message: PushMessage, notificationId: Int, quiet: Boolean): Boolean {
        val sendId = deliverySendId(message) ?: return false
        // Received already (poll of `pending`, a direct transfer): its notification is up, the push adds nothing.
        if (store.isHandled(sendId)) return true
        val known = store.bySend(sendId)
        val entry = known ?: GdReceived(
            key = "s:$sendId",
            sendId = sendId,
            from = null,
            at = System.currentTimeMillis(),
        )
        if (known == null) {
            notifier.placeholder(message.title, message.body, sendId, notificationId, message.id, quiet)
        } else {
            notifier.received(entry, notificationId, message.id, quiet)
        }
        shown[sendId] = notificationId
        return true
    }

    private val shown = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** A new delivery push: fetch the send (and save small files) in the background. */
    fun onNew(message: PushMessage) {
        val sendId = deliverySendId(message) ?: return
        val nid = shown.remove(sendId)
        if (nid == null && store.isHandled(sendId)) return
        GateDropReceiveWorker.enqueueSend(
            context,
            sendId = sendId,
            save = false,
            autoSave = true,
            show = nid != null,
            notificationId = nid,
            messageId = message.id,
            quiet = true,
            // A fetch of the same send from `pending` may be under way: this one replaces the placeholder after it.
            after = nid != null,
        )
    }

    /** `event: plugin` of GateDrop (a transfer offer sent as a realtime event). */
    fun onPluginEvent(type: String, data: JsonElement?) {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val root = o.get("gatedrop")?.takeIf { it.isJsonObject }?.asJsonObject ?: o
        if (!root.has("transfer") && type != "transfer") return
        val offer = GateDropJson.offer(root) ?: return
        scope.launch { GateDropReceiveWorker.enqueueOffer(context, offer, root.toString()) }
    }
}

/** Realtime events of the push stream for the chats and GateDrop. */
@Singleton
class AppPluginEventSink @Inject constructor(
    private val chats: com.gatecontrol.android.chat.ChatEventSink,
    private val gatedrop: dagger.Lazy<GateDropPushHandler>,
    private val gatedropRepository: dagger.Lazy<GateDropRepository>,
) : PluginEventSink {
    override fun onPluginEvent(plugin: String, type: String, data: JsonElement?) {
        if (plugin == GATEDROP_PLUGIN) {
            runCatching { gatedrop.get().onPluginEvent(type, data) }
            return
        }
        chats.onPluginEvent(plugin, type, data)
    }

    override fun onStreamOpen() {
        chats.onStreamOpen()
        // (Re)connected: what was sent to this device while it was offline.
        runCatching { gatedropRepository.get().pollPendingSoon() }
    }
}

/**
 * Buttons of GateDrop notifications: Kopieren (text, link), Speichern
 * (files of a send). NOT exported: only the app's own PendingIntents.
 */
class GateDropActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, GateDropEntryPoint::class.java)
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        when (intent.action) {
            ACTION_COPY -> {
                val entry = deps.gateDropRepository().store.get(key) ?: return
                val text = entry.link ?: entry.text ?: return
                copy(context, text, sensitive = false)
                entry.sendId?.let { id -> CoroutineScope(Dispatchers.IO).launch { runCatching { deps.gateDropRepository().api.opened(id) } } }
            }
            ACTION_COPY_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { copy(context, it, sensitive = false) }
            ACTION_SAVE -> {
                val entry = deps.gateDropRepository().store.get(key) ?: return
                val sendId = entry.sendId ?: return
                GateDropReceiveWorker.enqueueSend(
                    context,
                    sendId = sendId,
                    save = true,
                    autoSave = false,
                    show = true,
                    notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0).takeIf { it != 0 },
                    quiet = true,
                )
            }
        }
    }

    private fun copy(context: Context, text: String, sensitive: Boolean) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("GateDrop", text)
        if (sensitive && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        runCatching { cm.setPrimaryClip(clip) }
        // Android 13+ shows its own confirmation.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.gatedrop_copied, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_COPY = "com.gatecontrol.android.gatedrop.COPY"
        const val ACTION_COPY_TEXT = "com.gatecontrol.android.gatedrop.COPY_TEXT"
        const val ACTION_SAVE = "com.gatecontrol.android.gatedrop.SAVE"
        const val EXTRA_KEY = "com.gatecontrol.android.gatedrop.extra.KEY"
        const val EXTRA_TEXT = "com.gatecontrol.android.gatedrop.extra.TEXT"
        const val EXTRA_NOTIFICATION = "com.gatecontrol.android.gatedrop.extra.NOTIFICATION"
    }
}
