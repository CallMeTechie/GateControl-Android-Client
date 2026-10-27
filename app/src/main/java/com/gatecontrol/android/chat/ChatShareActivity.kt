package com.gatecontrol.android.chat

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import com.gatecontrol.android.MainActivity
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.gatedrop.GateDropJson
import com.gatecontrol.android.gatedrop.GateDropLogic
import com.gatecontrol.android.ui.gatedrop.GateDropShareSheet
import com.gatecontrol.android.ui.gatedrop.GateDropShareViewModel
import com.gatecontrol.android.ui.gatedrop.GdShareStage
import com.gatecontrol.android.ui.gatedrop.GdSource
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.push.PushNavTarget
import com.gatecontrol.android.push.PushNavigation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** A file shared from another app, copied into the app's cache. */
data class SharedFile(val path: String, val name: String, val mime: String, val bytes: Long)

/** What "Teilen → GateControl" brought: text and/or files. */
data class SharePayload(val text: String?, val files: List<SharedFile>) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && files.isEmpty()
}

/** Hand-over from [ChatShareActivity] to the chat screens (MainActivity reads no intent extras). */
object ChatShareHandover {
    private val _pending = MutableStateFlow<SharePayload?>(null)
    val pending: StateFlow<SharePayload?> = _pending.asStateFlow()

    fun offer(payload: SharePayload) {
        _pending.value = payload
    }

    /** Taken by the conversation the person picked. */
    fun take(): SharePayload? = _pending.value.also { _pending.value = null }

    fun clear() {
        _pending.value = null
    }
}

/**
 * Share target "Teilen → GateControl" (text, pictures, files). Exported —
 * the system's share sheet starts it — but switched on only while the
 * server offers the chats or GateDrop ([setEnabled]).
 *
 * With GateDrop the sheet "Teilen mit GateControl" opens right here over
 * the sharing app (people, own devices, NAS, link for externals); the
 * files are streamed into the app's cache only when sending. Without
 * GateDrop (and for a sharing shortcut of a chat) it works as before:
 * copies what was shared into the app's cache right here (the read
 * permission belongs to this activity), refusing the app's own files, then
 * lets the person pick a chat — or, through a sharing shortcut
 * ([ChatShortcuts]), opens that chat directly.
 */
@AndroidEntryPoint
class ChatShareActivity : ComponentActivity() {

    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var pluginDirectory: PluginDirectory
    @Inject lateinit var settingsRepository: SettingsRepository

    private val gateDrop: GateDropShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val plugins = pluginDirectory.state.value
        // Rotated while the GateDrop sheet is open: the sheet stays (its state lives in the view model).
        if (savedInstanceState != null && !(plugins.gatedrop && savedInstanceState.getBoolean(STATE_GATEDROP))) {
            finish()
            return
        }
        if (!setupRepository.isConfigured() || !(plugins.messengerTab || plugins.gatedrop)) {
            Toast.makeText(this, R.string.chat_share_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }
        // A sharing shortcut (share sheet → a recent chat): straight into that conversation.
        val target = ChatShortcutLogic.conversationOf(intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
        val uris: List<Uri> = if (action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        // GateDrop on the server: its sheet (people, own devices, NAS, link); the chats stay one tap away.
        if (plugins.gatedrop && target == null) {
            showGateDrop(uris, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(), chats = plugins.messengerTab)
            return
        }
        if (!plugins.messengerTab) {
            Toast.makeText(this, R.string.chat_share_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        shareInChat(target, uris, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_GATEDROP, gateDropShown)
    }

    private var gateDropShown = false

    /** GateDrop's sheet over the app that shares (this activity is translucent). */
    private fun showGateDrop(uris: List<Uri>, text: String?, chats: Boolean) {
        gateDropShown = true
        val accepted = uris.filter { ChatShareRules.acceptable(it, packageName) }
        val sources = accepted.take(GateDropJson.MAX_ITEMS).mapNotNull { uri ->
            val (name, size) = describe(uri)
            if (size != null && size > GateDropLogic.MAX_FILE_BYTES) return@mapNotNull null
            val mime = contentResolver.getType(uri)?.lowercase() ?: name?.substringAfterLast('.', "")?.lowercase()
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: GateDropJson.DEFAULT_MIME
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            GdSource(uri, GateDropJson.safeName(name, "file.$ext"), GateDropJson.mime(mime), size)
        }
        val skipped = uris.size - sources.size
        if (sources.isEmpty() && text.isNullOrBlank()) {
            Toast.makeText(this, R.string.chat_share_nothing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        gateDrop.start(sources, text?.take(GateDropJson.TEXT_MAX), skipped)
        val serverUrl = setupRepository.getServerUrl()
        setContent {
            val theme by settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = "system")
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            GateControlTheme(darkTheme = dark) {
                val ui by gateDrop.ui.collectAsStateWithLifecycle()
                LaunchedEffect(ui.stage) {
                    if (ui.stage == GdShareStage.DONE) {
                        Toast.makeText(this@ChatShareActivity, R.string.gatedrop_sending_toast, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                GateDropShareSheet(
                    ui = ui,
                    serverUrl = serverUrl,
                    onShareInChat = if (chats) {
                        { shareInChat(null, uris, text) }
                    } else {
                        null
                    },
                    onTogglePerson = gateDrop::togglePerson,
                    onToggleDevice = gateDrop::toggleDevice,
                    onToggleNas = gateDrop::toggleNas,
                    onToggleDrive = gateDrop::toggleDrive,
                    onNote = gateDrop::setNote,
                    onOpenLink = gateDrop::openLinkOptions,
                    onLinkDraft = gateDrop::setLinkDraft,
                    onApplyLink = gateDrop::applyLink,
                    onRemoveLink = gateDrop::removeLink,
                    onCloseLink = gateDrop::closeLinkOptions,
                    onRetry = gateDrop::loadTargets,
                    onSend = gateDrop::send,
                    onDismiss = { finish() },
                )
            }
        }
    }

    /** Today's chat sharing: copy into the cache, pick a chat (or the shortcut's) in the app. */
    private fun shareInChat(target: Long?, uris: List<Uri>, rawText: String?) {
        val text = rawText?.take(ChatJson.TEXT_MAX)
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { uris.take(MAX_FILES).mapNotNull { copy(it) } }
            val payload = SharePayload(text?.takeIf { it.isNotBlank() }, files)
            if (payload.isEmpty) {
                Toast.makeText(this@ChatShareActivity, R.string.chat_share_nothing, Toast.LENGTH_LONG).show()
            } else {
                ChatShareHandover.offer(payload)
                PushNavigation.request(PushNavTarget.ChatShare(target))
                startActivity(
                    Intent(this@ChatShareActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
            finish()
        }
    }

    private fun copy(uri: Uri): SharedFile? {
        if (!ChatShareRules.acceptable(uri, packageName)) {
            Timber.w("Chats: shared file refused")
            return null
        }
        return try {
            val (name, size) = describe(uri)
            if (size != null && size > MAX_BYTES) return null
            val mime = contentResolver.getType(uri)?.lowercase() ?: name?.substringAfterLast('.', "")?.lowercase()
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: "application/octet-stream"
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val dir = File(cacheDir, SHARE_DIR).apply { mkdirs() }
            val target = File(dir, UUID.randomUUID().toString() + "." + ext)
            var total = 0L
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            target.delete()
                            return null
                        }
                        out.write(buf, 0, n)
                    }
                }
            } ?: return null
            SharedFile(target.path, ChatMime.safeName(name, ext), mime, total)
        } catch (e: Exception) {
            Timber.w("Chats: shared file not read (%s)", e.javaClass.simpleName)
            null
        }
    }

    private fun describe(uri: Uri): Pair<String?, Long?> = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val s = c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            n to s
        } ?: (null to null)
    }.getOrDefault(null to null)

    companion object {
        const val SHARE_DIR = "chat-share"
        private const val STATE_GATEDROP = "gatedrop"
        private const val MAX_FILES = 10
        private const val MAX_BYTES = 64L * 1024 * 1024

        /** On while the server offers the chats or GateDrop: only then GateControl shows up in the share sheet. */
        fun setEnabled(context: Context, enabled: Boolean) {
            runCatching {
                val component = ComponentName(context, ChatShareActivity::class.java)
                val want = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                if (context.packageManager.getComponentEnabledSetting(component) != want) {
                    context.packageManager.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
                }
            }.onFailure { Timber.w("Chats: share target not switched (%s)", it.javaClass.simpleName) }
        }

        /** Shared files older than a day are left-overs. */
        fun pruneCache(context: Context) {
            val dir = File(context.cacheDir, SHARE_DIR)
            val cutoff = System.currentTimeMillis() - 24 * 3600_000L
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }
}

/** Which shared URIs may be read. Pure, unit-tested. */
object ChatShareRules {
    /**
     * Only `content://` of another app: never `file://` (another app could
     * name this app's private files) and never this app's own providers.
     */
    fun acceptable(uri: Uri, ownPackage: String): Boolean = acceptable(uri.scheme, uri.authority, ownPackage)

    fun acceptable(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (scheme != ContentResolver.SCHEME_CONTENT) return false
        val a = authority?.lowercase() ?: return false
        if (a.isBlank()) return false
        val own = ownPackage.lowercase()
        // Our FileProvider is "<applicationId>.provider"; any authority of ours starts with the package.
        return !(a == own || a.startsWith("$own.") || a.contains("@$own") || a.startsWith("com.gatecontrol."))
    }
}
