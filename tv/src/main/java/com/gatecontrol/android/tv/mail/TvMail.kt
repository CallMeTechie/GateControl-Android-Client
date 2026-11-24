package com.gatecontrol.android.tv.mail

import android.content.Context
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MAi
import com.gatecontrol.android.mail.MAttachment
import com.gatecontrol.android.mail.MBulkUndo
import com.gatecontrol.android.mail.MChanged
import com.gatecontrol.android.mail.MDraft
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMessage
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSend
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailApi
import com.gatecontrol.android.mail.MailBulk
import com.gatecontrol.android.mail.MailJson
import com.gatecontrol.android.mail.notOk
import com.gatecontrol.android.tv.zuhause.TvSourceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the TV loaded of Mail: `GET me` and each chosen account's inbox, each with how it answered. */
data class TvMailData(
    val me: MMe? = null,
    val meState: TvSourceState = TvSourceState.LOADING,
    /** Per account id: the newest mails of its inbox. */
    val lists: Map<Long, List<MSummary>> = emptyMap(),
    val listStates: Map<Long, TvSourceState> = emptyMap(),
)

/** How a change went: done, refused by the server, or no connection. */
enum class TvMailDone { OK, REFUSED, OFFLINE }

/** Archived or deleted: the token of „Rückgängig“ (Mail 1.13 `undo`), null when the server offers none. */
data class TvMailMoved(val done: TvMailDone, val undo: MBulkUndo?)

/** `send` went: the outbox entry to stop within [windowMs]. */
data class TvMailSentOk(val send: MSend, val windowMs: Long)

/**
 * „Mail“ of GateControl TV: the inbox of the accounts chosen on the TV (Einstellungen › Nachrichten, none by default), a mail
 * to read, its AI („Kurz gesagt“, answer suggestions), answering through the Mail plugin's drafts and the rest of its
 * app API ([MailApi], the phone app's), with the rights of the person who paired the TV. Nothing is kept on the stick
 * but the attachment opened last (in the cache). No new mails to typed addresses: a forward goes to someone the
 * plugin knows.
 */
@Singleton
class TvMail @Inject constructor(
    private val api: MailApi,
    private val settings: TvMailSettings,
    @ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _data = MutableStateFlow(TvMailData())
    val data: StateFlow<TvMailData> = _data.asStateFlow()

    @Volatile
    private var last = 0L

    @Volatile
    private var lastChosen: Set<Long> = emptySet()

    /** `GET me` and the chosen inboxes; at most every [MIN_GAP_MS] unless [force]d or the choice changed. */
    suspend fun refresh(force: Boolean = false) = mutex.withLock {
        val chosen = settings.accounts.value
        val now = System.currentTimeMillis()
        if (!force && chosen == lastChosen && now - last < MIN_GAP_MS && _data.value.meState == TvSourceState.OK) return@withLock
        last = now
        lastChosen = chosen
        val me = api.me()
        if (me is MResult.Ok) {
            val value = me.value.first
            // an account removed on the phone is no longer chosen here
            settings.keepOnly(value.accounts.map { it.id })
            _data.update { it.copy(me = value, meState = TvSourceState.OK) }
        } else {
            _data.update { it.copy(meState = mailState(me)) }
            if (_data.value.me == null) return@withLock
        }
        val accounts = TvMailLogic.accounts(_data.value.me, settings.accounts.value)
        // lists of accounts no longer chosen go at once
        _data.update { d -> d.copy(lists = d.lists.filterKeys { id -> accounts.any { it.id == id } }) }
        for (a in accounts) loadInbox(a.id)
    }

    fun refreshAsync(force: Boolean = false) {
        scope.launch { refresh(force) }
    }

    private suspend fun loadInbox(accountId: Long) {
        val r = api.messages(TvMailLogic.inboxQuery(accountId))
        _data.update { d ->
            if (r is MResult.Ok) {
                d.copy(lists = d.lists + (accountId to r.value.first.items), listStates = d.listStates + (accountId to TvSourceState.OK))
            } else {
                d.copy(listStates = d.listStates + (accountId to mailState(r)))
            }
        }
    }

    /** A mail to read; only one of an account chosen on the TV. */
    suspend fun message(id: Long): MResult<MMessage> {
        val r = api.message(id)
        if (r !is MResult.Ok) return r.notOk()
        val m = r.value.first
        return if (m.summary.accountId in settings.accounts.value) MResult.Ok(m) else MResult.Failed("not_on_tv", 403)
    }

    /** The AI's result that is there (`GET ai/<id>`), [run]: made now when there is none yet (`POST`, rate-limited). */
    suspend fun ai(messageId: Long, run: Boolean = false): MAi? {
        val r = api.ai(messageId)
        val got = (r as? MResult.Ok)?.value
        if (got?.done == true || !run || r is MResult.Failed) return got?.takeIf { it.done }
        return (api.runAi(messageId) as? MResult.Ok)?.value?.takeIf { it.done }
    }

    /** Shown long enough: read (the list shows it at once). */
    suspend fun markRead(id: Long): TvMailDone {
        setUnread(id, false)
        val r = api.flags(listOf(id), unread = false)
        if (r !is MResult.Ok) setUnread(id, true)
        return mailOutcome(r)
    }

    /** Archive or delete: out of the list at once, back when the server says no. */
    suspend fun move(id: Long, action: MailBulk.Action): TvMailMoved {
        val before = _data.value.lists
        _data.update { d -> d.copy(lists = d.lists.mapValues { (_, l) -> l.filter { it.id != id } }) }
        val r = api.bulk(action.path, MailBulk.body(action, listOf(id), threads = false))
        if (r !is MResult.Ok) {
            _data.update { d -> d.copy(lists = before.filterKeys { it in d.lists.keys }) }
            return TvMailMoved(mailOutcome(r), null)
        }
        return TvMailMoved(TvMailDone.OK, r.value.undo)
    }

    /** „Rückgängig“ of an archive or delete: the mail comes back, the inboxes as the server says. */
    suspend fun undoMove(token: String): TvMailDone {
        val r = api.undo(token)
        if (r is MResult.Ok) refresh(force = true)
        return mailOutcome(r)
    }

    // ─── answering ──────────────────────────────────────────────────

    /** The draft of an answer or forward, filled in by the server. */
    suspend fun startDraft(mode: TvMailMode, messageId: Long): MResult<MDraft> = api.createDraft(TvMailLogic.createBody(mode, messageId))

    suspend fun draft(id: Long): MResult<MDraft> = api.draft(id)

    /** What was written on the TV into the draft (it lies on the server: a lost connection loses nothing). */
    suspend fun save(draft: MDraft, mode: TvMailMode, messageId: Long, text: String, to: List<MAddress>?): MResult<MDraft> =
        api.updateDraft(draft.id, TvMailLogic.updateBody(draft, mode, messageId, text, to))

    /** The draft saved with [text], then sent: „Rückgängig“ for the window the server keeps. */
    suspend fun send(draft: MDraft, mode: TvMailMode, messageId: Long, text: String, to: List<MAddress>?, now: () -> Long = System::currentTimeMillis): MResult<TvMailSentOk> {
        val saved = save(draft, mode, messageId, text, to)
        if (saved !is MResult.Ok) return saved.notOk()
        val started = now()
        return when (val r = api.send(saved.value.id, null)) {
            is MResult.Ok -> {
                val at = now()
                MResult.Ok(TvMailSentOk(r.value, TvMailLogic.undoWindow(r.value.undoUntil, at, at - started)))
            }
            else -> r.notOk()
        }
    }

    /** „Rückgängig“ after sending: the send stops, the draft it came from (null: the server named none). */
    suspend fun cancelSend(outboxId: Long): MResult<Long?> = api.cancel(outboxId)

    /** „Verwerfen“: the draft goes from the server. */
    suspend fun discard(draftId: Long) {
        api.deleteDraft(draftId)
    }

    /** Whom a mail can be forwarded to: people the plugin knows ([query] empty: those written with lately). */
    suspend fun forwardTargets(query: String): List<MAddress>? =
        (api.contacts(query.trim()) as? MResult.Ok)?.value?.let(TvMailLogic::forwardTargets)

    // ─── attachments ────────────────────────────────────────────────

    /** A photo or PDF of a mail as a file on the stick (in the cache, only the last few); null when it cannot be had. */
    suspend fun attachment(messageId: Long, a: MAttachment): File? {
        if (!TvMailLogic.canOpen(a)) return null
        val media = (api.attachmentMedia(messageId, a.id) as? MResult.Ok)?.value ?: return null
        if (media.scan?.blocked == true) return null
        val body = (api.media(media.mediaId) as? MResult.Ok)?.value ?: return null
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            val file = File(dir, TvMailLogic.cacheName(messageId, a))
            try {
                body.use { b ->
                    b.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > TvMailLogic.MAX_OPEN_BYTES) throw java.io.IOException("too large")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
                trim(dir, keep = file)
                file
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (_: Exception) {
                file.delete()
                null
            }
        }
    }

    /** A picture of the mail's HTML (`cid/…`, `img/…` the server let through) with the app's sign-in. */
    suspend fun picture(path: String): Pair<ByteArray, String>? = when (val p = api.picture(path)) {
        is com.gatecontrol.android.mail.MPicture.Got -> p.bytes to p.mime
        else -> null
    }

    // ─── live ───────────────────────────────────────────────────────

    private val _changes = kotlinx.coroutines.flow.MutableSharedFlow<MChanged>(extraBufferCapacity = 8, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** The plugin's live event `mail` (a mail came or changed, a body that was missing is there). */
    val changes: kotlinx.coroutines.flow.SharedFlow<MChanged> = _changes.asSharedFlow()

    @Volatile
    private var liveJob: kotlinx.coroutines.Job? = null

    /**
     * A realtime event of the Mail plugin ([TvMailEvents]): the inboxes load again shortly after (several events at
     * once load once); `calendar` and `contacts` are not the TV's. Only while accounts are chosen.
     */
    fun onEvent(type: String, data: com.google.gson.JsonElement?) {
        if (type == "calendar" || type == "contacts") return
        val changed = MailJson.changed(data) ?: if (type == "mail" || type == "changed") MChanged(null, null) else return
        val chosen = settings.accounts.value
        if (chosen.isEmpty() || (changed.accountId != null && changed.accountId !in chosen)) return
        _changes.tryEmit(changed)
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            kotlinx.coroutines.delay(LIVE_DELAY_MS)
            refresh(force = true)
        }
    }

    /** A new push connection: what came while offline (only once Mail was loaded). */
    fun onStreamOpen() {
        if (settings.accounts.value.isNotEmpty() && _data.value.me != null) refreshAsync(force = true)
    }

    /** Unpaired: nothing of this login stays (the choice of accounts neither). */
    fun clear() {
        _data.value = TvMailData()
        last = 0L
        lastChosen = emptySet()
        settings.clear()
        runCatching { File(context.cacheDir, CACHE_DIR).deleteRecursively() }
    }

    private fun setUnread(id: Long, unread: Boolean) = _data.update { d ->
        d.copy(lists = d.lists.mapValues { (_, l) -> l.map { if (it.id == id) it.copy(unread = unread) else it } })
    }

    private fun trim(dir: File, keep: File) {
        val files = dir.listFiles()?.filter { it != keep }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP_FILES - 1).forEach { it.delete() }
    }

    private companion object {
        const val MIN_GAP_MS = 30_000L
        const val LIVE_DELAY_MS = 500L
        const val CACHE_DIR = "tv-mail"
        const val KEEP_FILES = 3
    }
}

internal fun mailState(r: MResult<*>): TvSourceState = when (r) {
    is MResult.Ok -> TvSourceState.OK
    MResult.Offline, MResult.NotConfigured -> TvSourceState.OFFLINE
    is MResult.Failed -> TvSourceState.MISSING
}

internal fun mailOutcome(r: MResult<*>): TvMailDone = when (r) {
    is MResult.Ok -> TvMailDone.OK
    MResult.Offline, MResult.NotConfigured -> TvMailDone.OFFLINE
    is MResult.Failed -> TvMailDone.REFUSED
}
