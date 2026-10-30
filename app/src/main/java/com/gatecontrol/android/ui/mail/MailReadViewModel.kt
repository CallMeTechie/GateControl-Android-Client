package com.gatecontrol.android.ui.mail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAi
import com.gatecontrol.android.mail.MAttachment
import com.gatecontrol.android.mail.MChanged
import com.gatecontrol.android.mail.MPicture
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMessage
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSavedTo
import com.gatecontrol.android.mail.MSuggestion
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailCache
import com.gatecontrol.android.mail.MailJson
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.mail.notOk
import com.gatecontrol.android.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** The AI bar of a mail. */
sealed class MailAiState {
    /** No AI for this mail (not offered, the account's AI off). */
    data object Off : MailAiState()
    data object Loading : MailAiState()
    data class Ready(val ai: MAi) : MailAiState()
    data class Error(val code: String) : MailAiState()
}

/** One attachment's Drive state on the screen. */
data class MailAttachmentUi(
    val attachment: MAttachment,
    val suggestion: MSuggestion? = attachment.suggestion,
    val savedTo: MSavedTo? = attachment.savedTo,
    val duplicate: Boolean = false,
    val busy: Boolean = false,
    /** The provider still sends it (`409 busy { reason: 'loading' }`): asked again by itself. */
    val loading: Boolean = false,
)

data class MailReadState(
    val message: MMessage? = null,
    val loading: Boolean = true,
    val offline: Boolean = false,
    val error: String? = null,
    /** The mails of the conversation (`GET threads/<id>`, this one among them), oldest first; empty until loaded. */
    val conversation: List<MMessage> = emptyList(),
    /** "Unterhaltung · n Mails" was tapped: all its mails show, the open one unfolded. */
    val threadOpen: Boolean = false,
    val threadLoading: Boolean = false,
    /** The other mails of the conversation unfolded. */
    val threadUnfolded: Set<Long> = emptySet(),
    val attachments: List<MailAttachmentUi> = emptyList(),
    val ai: MailAiState = MailAiState.Off,
    val imagesBusy: Boolean = false,
    val savingAll: Boolean = false,
    /** Embedded pictures the provider still sends (`cid/…` answered `busy`). */
    val picturesLoading: Boolean = false,
    /** Counts up when the HTML is to load its pictures again (one came in the meantime). */
    val pictureRound: Int = 0,
)

/** Reading a mail (mockup "Mail lesen"): the AI bar, the attachments with "In Drive speichern", pictures, the thread. */
@HiltViewModel
class MailReadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val api = repository.api
    private val cache = repository.cache
    val id: Long = saved.get<String>(Screen.MailMessage.ARG_ID)?.toLongOrNull() ?: 0L

    val me: StateFlow<MMe?> = repository.me
    val folders: StateFlow<Map<Long, List<com.gatecontrol.android.mail.MFolder>>> = repository.folders

    private val _state = MutableStateFlow(MailReadState())
    val state: StateFlow<MailReadState> = _state.asStateFlow()

    private val _event = MutableStateFlow<MailReadEvent?>(null)
    val event: StateFlow<MailReadEvent?> = _event.asStateFlow()

    private var aiTried = false
    private var extrasLoaded = false
    private var loadJob: Job? = null
    /** An event came while `GET messages/<id>` was still on its way: ask once more when it answers without a body. */
    private var loadAgain = false

    /** Attachments to open once the provider sent them: their id → how often they were asked for again. */
    private val waitingOpen = ConcurrentHashMap<Long, Int>()
    private val openRetry = ConcurrentHashMap<Long, Job>()

    /** Embedded pictures the provider still sends (attachment ids) and the HTML's next try. */
    private val waitingCid: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private var pictureTries = 0
    private var pictureRetry: Job? = null

    /** The HTML's pictures as they came (the page loads them again after a [MailReadState.pictureRound]). */
    private val pictures = object : LinkedHashMap<String, MPicture.Got>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MPicture.Got>?): Boolean = picturesBytes() > PICTURES_MAX_BYTES
    }

    init {
        load()
        viewModelScope.launch { repository.changed.collect(::onChanged) }
    }

    fun load() {
        if (id <= 0) {
            _state.value = MailReadState(loading = false, error = "not_found")
            return
        }
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            if (_state.value.message == null) cache.message(id)?.let { show(it, loading = true) }
            loadAgain = false
            when (val r = api.message(id)) {
                is MResult.Ok -> {
                    val (m, raw) = r.value
                    // the snippet stands in for a body not here yet: a full copy kept earlier stays
                    if (m.bodyMissing) cache.message(id)?.takeIf { !it.bodyMissing && (it.html != null || !it.text.isNullOrBlank()) }?.let { show(it, loading = false) } ?: show(m, loading = false)
                    else {
                        show(m, loading = false)
                        cache.putMessage(raw)
                    }
                    if (m.summary.unread) markRead(m)
                    if (!m.bodyMissing && !extrasLoaded) {
                        extrasLoaded = true
                        loadExtras(m)
                    }
                }
                else -> _state.update {
                    it.copy(loading = false, offline = r is MResult.Offline && it.message != null, error = if (it.message == null) MailHomeViewModel.code(r) else null)
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (loadAgain && _state.value.message?.bodyMissing == true) viewModelScope.launch { load() }
            }
        }
    }

    /**
     * The live event `mail` (Mail 1.1.2): this mail's body (or its conversation's) is there now → load it again; an
     * attachment or embedded picture the provider was still sending is there → open it, or the pictures load again.
     */
    private fun onChanged(c: MChanged) {
        val m = _state.value.message
        if (m != null && m.bodyMissing && MailLogic.concerns(c, id, m.summary.threadId)) {
            if (loadJob?.isActive == true) loadAgain = true else load()
        }
        val aid = c.attachmentId ?: return
        if (c.messageId != id) return
        if (waitingOpen.containsKey(aid)) {
            openRetry.remove(aid)?.cancel()
            _state.value.attachments.firstOrNull { it.attachment.id == aid }?.let { viewModelScope.launch { openNow(it.attachment) } }
        }
        if (waitingCid.remove(aid)) reloadPictures()
    }

    private fun show(m: MMessage, loading: Boolean) {
        _state.update { s ->
            s.copy(
                message = m,
                loading = loading,
                error = null,
                attachments = m.attachments.filter { !it.inline }.map { a ->
                    // what the screen learned already (a save, a suggestion) stays
                    s.attachments.firstOrNull { it.attachment.id == a.id }?.copy(attachment = a, savedTo = a.savedTo ?: s.attachments.firstOrNull { it.attachment.id == a.id }?.savedTo)
                        ?: MailAttachmentUi(a)
                },
            )
        }
    }

    private fun markRead(m: MMessage) {
        viewModelScope.launch {
            if (api.flags(listOf(m.id), unread = false) is MResult.Ok) {
                _state.update { s -> s.copy(message = s.message?.let { it.copy(summary = it.summary.copy(unread = false)) }) }
                repository.refreshSoon(0)
            }
        }
    }

    private suspend fun loadExtras(m: MMessage) {
        val me = repository.me.value ?: repository.refresh().valueOrNull()
        val account = me?.account(m.summary.accountId)
        // the conversation (when the person groups by conversation)
        if (me?.settings?.threads != false && hasThread(m)) loadThread()
        // the AI bar: only where the server offers AI and the account allows it
        if (me?.has(MMe.FEATURE_AI) == true && account?.ai != false) {
            if (_state.value.ai !is MailAiState.Ready) _state.update { it.copy(ai = MailAiState.Loading) }
            when (val r = api.ai(m.id)) {
                is MResult.Ok -> if (r.value.done) _state.update { it.copy(ai = MailAiState.Ready(r.value)) } else runAi()
                else -> _state.update { it.copy(ai = aiError(r)) }
            }
        }
        // Drive: a suggestion for each attachment that has none yet
        if (me?.has(MMe.FEATURE_DRIVE) == true) {
            _state.value.attachments.filter { it.savedTo == null && it.suggestion == null }.take(MAX_SUGGEST).forEach { a ->
                api.suggest(m.id, a.attachment.id).valueOrNull()?.let { s ->
                    updateAttachment(a.attachment.id) { it.copy(suggestion = s.suggestion, duplicate = s.duplicate) }
                }
            }
        }
    }

    private fun hasThread(m: MMessage): Boolean = m.summary.threadId != null && m.summary.threadCount > 1

    /** The conversation's mails (`GET threads/<id>`, oldest first). */
    private suspend fun loadThread() {
        val threadId = _state.value.message?.summary?.threadId ?: return
        if (_state.value.threadLoading) return
        _state.update { it.copy(threadLoading = true) }
        val r = api.thread(threadId)
        _state.update { s ->
            when (r) {
                is MResult.Ok -> s.copy(conversation = r.value, threadLoading = false)
                else -> s.copy(threadLoading = false)
            }
        }
        if (r !is MResult.Ok && _state.value.threadOpen) _event.value = MailReadEvent.Toast(mailText(context, MailHomeViewModel.code(r)))
    }

    /** A tap on "Unterhaltung · n Mails": all mails of the conversation show (loaded now when not yet), or fold again. */
    fun toggleThread() {
        val open = !_state.value.threadOpen
        _state.update { it.copy(threadOpen = open) }
        if (open && _state.value.conversation.isEmpty()) viewModelScope.launch { loadThread() }
    }

    /** Another mail of the conversation unfolded or folded. */
    fun toggleThreadMail(id: Long) {
        _state.update { s -> s.copy(threadUnfolded = if (id in s.threadUnfolded) s.threadUnfolded - id else s.threadUnfolded + id) }
    }

    private fun aiError(r: MResult<*>): MailAiState = when ((r as? MResult.Failed)?.code) {
        "ai_off" -> MailAiState.Off
        else -> MailAiState.Error(MailHomeViewModel.code(r))
    }

    /** "Kurz gesagt" etc. made now (`POST ai/<id>`), once per opening unless asked again. */
    fun runAi(force: Boolean = false) {
        if (aiTried && !force) return
        aiTried = true
        _state.update { it.copy(ai = MailAiState.Loading) }
        viewModelScope.launch {
            when (val r = api.runAi(id, again = force)) {
                is MResult.Ok -> _state.update { it.copy(ai = if (r.value.done) MailAiState.Ready(r.value) else MailAiState.Off) }
                else -> _state.update { it.copy(ai = aiError(r)) }
            }
        }
    }

    /** A to-do into the family calendar (Familie's `calendar_add`). */
    fun todoToFamily(index: Int) {
        viewModelScope.launch {
            _event.value = when (val r = api.todoToFamily(id, index)) {
                is MResult.Ok -> MailReadEvent.Toast(context.getString(R.string.mail_todo_added, r.value.ifEmpty { context.getString(R.string.mail_family_calendar) }))
                else -> MailReadEvent.Toast(mailText(context, MailHomeViewModel.code(r)))
            }
        }
    }

    /** "Bilder laden" (once) or "Immer für diesen Absender". */
    fun allowImages(sender: Boolean) {
        _state.update { it.copy(imagesBusy = true) }
        viewModelScope.launch {
            when (val r = api.allowImages(id, sender)) {
                is MResult.Ok -> {
                    show(r.value.first, loading = false)
                    cache.putMessage(r.value.second)
                    // the HTML is the same as before (its pictures' addresses do not change): the page loads them now
                    imagesAllowed(sender)
                }
                else -> _event.value = MailReadEvent.Toast(mailText(context, MailHomeViewModel.code(r)))
            }
            _state.update { it.copy(imagesBusy = false) }
        }
    }

    /** The bar's and the menu's actions; those that take the mail away close the screen. */
    fun act(action: String, folderId: Long? = null) {
        val m = _state.value.message ?: return
        val ids = listOf(m.id)
        viewModelScope.launch {
            val r = when (action) {
                MailLogic.SWIPE_ARCHIVE -> api.archive(ids)
                MailLogic.SWIPE_DELETE -> api.delete(ids)
                "junk" -> api.junk(ids)
                "unread" -> api.flags(ids, unread = true)
                "flag" -> api.flags(ids, flagged = !m.summary.flagged)
                "tab" -> api.setTab(ids, if (m.summary.tab == MSummary.TAB_OTHER) MSummary.TAB_RELEVANT else MSummary.TAB_OTHER)
                "move" -> api.move(ids, folderId ?: return@launch)
                else -> return@launch
            }
            when (r) {
                is MResult.Ok -> {
                    repository.refreshSoon(0)
                    when (action) {
                        "flag" -> _state.update { s -> s.copy(message = s.message?.let { it.copy(summary = it.summary.copy(flagged = !it.summary.flagged)) }) }
                        "tab" -> {
                            _state.update { s ->
                                s.copy(message = s.message?.let { it.copy(summary = it.summary.copy(tab = if (it.summary.tab == MSummary.TAB_OTHER) MSummary.TAB_RELEVANT else MSummary.TAB_OTHER)) })
                            }
                            _event.value = MailReadEvent.Toast(context.getString(R.string.mail_tab_learned))
                        }
                        else -> {
                            cache.forgetMessages(ids)
                            _event.value = MailReadEvent.Close
                        }
                    }
                }
                else -> _event.value = MailReadEvent.Toast(mailText(context, MailHomeViewModel.code(r)))
            }
        }
    }

    private fun updateAttachment(aid: Long, change: (MailAttachmentUi) -> MailAttachmentUi) {
        _state.update { s -> s.copy(attachments = s.attachments.map { if (it.attachment.id == aid) change(it) else it }) }
    }

    /** "In Drive speichern": into the suggested folder (its name), or into [folderId] the person picked. */
    fun saveToDrive(a: MailAttachmentUi, folderId: Long?, remember: Boolean) {
        val target = folderId ?: a.suggestion?.folderId
        val name = if (folderId == null) a.suggestion?.name else null
        updateAttachment(a.attachment.id) { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = api.saveToDrive(id, a.attachment.id, target, name, remember)) {
                is MResult.Ok -> {
                    updateAttachment(a.attachment.id) { it.copy(busy = false, savedTo = r.value) }
                    _event.value = MailReadEvent.Toast(context.getString(R.string.mail_saved_in, r.value.where))
                }
                else -> {
                    updateAttachment(a.attachment.id) { it.copy(busy = false) }
                    _event.value = MailReadEvent.Toast(mailText(context, MailHomeViewModel.code(r)))
                }
            }
        }
    }

    fun saveAll() {
        _state.update { it.copy(savingAll = true) }
        viewModelScope.launch {
            when (val r = api.saveAll(id)) {
                is MResult.Ok -> {
                    r.value.saved.forEach { (aid, to) -> updateAttachment(aid) { it.copy(savedTo = to) } }
                    _event.value = MailReadEvent.Toast(
                        context.resources.getQuantityString(R.plurals.mail_saved_all, r.value.saved.size, r.value.saved.size) +
                            if (r.value.failed.isNotEmpty()) " · " + context.resources.getQuantityString(R.plurals.mail_saved_failed, r.value.failed.size, r.value.failed.size) else "",
                    )
                }
                else -> _event.value = MailReadEvent.Toast(mailText(context, MailHomeViewModel.code(r)))
            }
            _state.update { it.copy(savingAll = false) }
        }
    }

    /** An attachment opened in another app: fetched into `cache/mail-open/<mail>-<attachment>/<name>`. */
    fun open(a: MAttachment) {
        if (waitingOpen.containsKey(a.id)) return
        waitingOpen[a.id] = 0
        viewModelScope.launch { openNow(a) }
    }

    /**
     * Fetches and opens [a]. While the provider still sends it (`409 busy { reason: 'loading', retryAfter }`) it shows
     * "Wird gerade geladen …" and asks again after `retryAfter` (at most [MailLogic.BUSY_RETRIES] times), or at once
     * when the live event names it.
     */
    private suspend fun openNow(a: MAttachment) {
        updateAttachment(a.id) { it.copy(busy = true) }
        val file = fetch(a)
        val busy = (file as? MResult.Failed)?.takeIf { MailJson.busyRetryAfter(it.code, it.error) != null }
        val tries = waitingOpen[a.id] ?: 0
        if (busy != null && tries < MailLogic.BUSY_RETRIES) {
            waitingOpen[a.id] = tries + 1
            updateAttachment(a.id) { it.copy(busy = false, loading = true) }
            openRetry.remove(a.id)?.cancel()
            openRetry[a.id] = viewModelScope.launch {
                delay(MailLogic.retryDelayMs(MailJson.busyRetryAfter(busy.code, busy.error)))
                openRetry.remove(a.id)
                openNow(a)
            }
            return
        }
        waitingOpen.remove(a.id)
        openRetry.remove(a.id)?.cancel()
        updateAttachment(a.id) { it.copy(busy = false, loading = false) }
        _event.value = when {
            file is MResult.Ok -> MailReadEvent.Open(file.value, a.mime)
            busy != null && MailJson.busyLoading(busy.code, busy.error) -> MailReadEvent.Toast(context.getString(R.string.mail_attachment_still_loading))
            else -> MailReadEvent.Toast(mailText(context, MailHomeViewModel.code(file)))
        }
    }

    private suspend fun fetch(a: MAttachment): MResult<File> {
        val dir = File(context.cacheDir, "${MailCache.OPEN_DIR}/$id-${a.id}")
        val target = File(dir, MailLogic.fileName(a.name))
        if (target.isFile && target.length() > 0) return MResult.Ok(target)
        val media = api.attachmentMedia(id, a.id)
        if (media !is MResult.Ok) return media.notOk()
        val body = api.media(media.value.mediaId)
        if (body !is MResult.Ok) return MResult.Failed(MailHomeViewModel.code(body), 0)
        return withContext(Dispatchers.IO) {
            runCatching {
                pruneOpened()
                dir.mkdirs()
                val tmp = File(dir, target.name + ".part")
                body.value.use { b -> b.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } } }
                if (!tmp.renameTo(target)) error("rename")
                MResult.Ok(target) as MResult<File>
            }.getOrElse { MResult.Failed("failed", 0) }
        }
    }

    /** Files opened more than a day ago go. */
    private fun pruneOpened() {
        val root = File(context.cacheDir, MailCache.OPEN_DIR)
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    /**
     * A picture of the HTML (`img/…`, `cid/…`) through the plugin, with the app's sign-in (called on the WebView's
     * threads). An embedded picture the provider still sends (`busy`) is left out for now: the page loads its pictures
     * again after `retryAfter` (at most [MailLogic.BUSY_RETRIES] times) or when the live event names it.
     */
    suspend fun picture(path: String): Pair<ByteArray, String>? {
        synchronized(pictures) { pictures[path] }?.let { return it.bytes to it.mime }
        return when (val r = api.picture(path)) {
            is MPicture.Got -> {
                synchronized(pictures) { pictures[path] = r }
                MailLogic.cidAttachment(path)?.let { aid ->
                    if (waitingCid.remove(aid) && waitingCid.isEmpty()) _state.update { it.copy(picturesLoading = false) }
                }
                r.bytes to r.mime
            }
            is MPicture.Busy -> {
                MailLogic.cidAttachment(path)?.let { aid -> pictureBusy(aid, r.retryAfter) }
                null
            }
            MPicture.None -> null
        }
    }

    private fun picturesBytes(): Long = pictures.values.sumOf { it.bytes.size.toLong() }

    private fun pictureBusy(attachmentId: Long, retryAfter: Int) {
        waitingCid.add(attachmentId)
        if (pictureTries >= MailLogic.BUSY_RETRIES) {
            _state.update { it.copy(picturesLoading = false) }
            return
        }
        _state.update { it.copy(picturesLoading = true) }
        synchronized(this) {
            if (pictureRetry?.isActive == true) return
            pictureRetry = viewModelScope.launch {
                delay(MailLogic.retryDelayMs(retryAfter))
                pictureTries++
                reloadPictures(fromTimer = true)
            }
        }
    }

    /**
     * The pictures were allowed (`POST messages/<id>/images`): those the page was refused are asked for again — none of
     * them is kept (a refusal never is, and the WebView keeps no answer); always for the [sender]: the conversation's
     * mails come again with theirs.
     */
    private fun imagesAllowed(sender: Boolean) {
        reloadPictures()
        if (sender && _state.value.conversation.isNotEmpty()) viewModelScope.launch { loadThread() }
    }

    /** The page asks for its pictures again (those it has come from [pictures]). */
    private fun reloadPictures(fromTimer: Boolean = false) {
        if (!fromTimer) synchronized(this) {
            pictureRetry?.cancel()
            pictureRetry = null
        }
        _state.update { it.copy(pictureRound = it.pictureRound + 1) }
    }

    fun consumed() {
        _event.value = null
    }

    private companion object {
        const val MAX_SUGGEST = 5
        const val PICTURES_MAX_BYTES = 16L * 1024 * 1024
    }
}

sealed class MailReadEvent {
    data object Close : MailReadEvent()
    data class Toast(val text: String) : MailReadEvent()
    data class Open(val file: File, val mime: String) : MailReadEvent()
}
