package com.gatecontrol.android.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatLocation
import com.gatecontrol.android.chat.ChatMediaStore
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatMime
import com.gatecontrol.android.chat.ChatPreview
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatShareHandover
import com.gatecontrol.android.chat.ChatTimeline
import com.gatecontrol.android.chat.PrepareError
import com.gatecontrol.android.chat.PrepareException
import com.gatecontrol.android.chat.SharePayload
import com.gatecontrol.android.chat.TimelineItem
import com.gatecontrol.android.chat.VoicePlayer
import com.gatecontrol.android.chat.VoiceRecorder
import com.gatecontrol.android.familie.FamilieGeocoder
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.location.LocateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

data class ConversationUiState(
    val conversationId: Long? = null,
    val conversation: ChatConversation? = null,
    val items: List<TimelineItem> = emptyList(),
    val profile: ChatProfile? = null,
    /** Users typing now (without the caller). */
    val typing: List<Long> = emptyList(),
    val hasOlder: Boolean = true,
    val loadingOlder: Boolean = false,
    val replyTo: ChatMessage? = null,
    val editing: ChatMessage? = null,
    /** Shared from another app, waiting for "Senden". */
    val shared: SharePayload? = null,
    val busy: Boolean = false,
    val searchResults: List<ChatMessage>? = null,
    val searching: Boolean = false,
    val loaded: Boolean = false,
    /** The conversation is gone for the caller (left, removed). */
    val gone: Boolean = false,
) {
    val myId: Long? get() = profile?.user?.userId
}

/** One-shot notices of the conversation screen. */
sealed interface ConversationNotice {
    data class Error(val code: String?) : ConversationNotice
    data class PrepareFailed(val error: PrepareError) : ConversationNotice
    data class Location(val result: LocateResult) : ConversationNotice
    data object RecordingFailed : ConversationNotice
    data object Left : ConversationNotice
}

/**
 * One conversation: its messages (cache + outbox) with day separators and
 * ticks, catching up when it opens, sending (text, pictures, files, voice,
 * location — all through the outbox), replies, edits, deletions,
 * reactions, read marks, "schreibt …", `active` while visible and search.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChatRepository,
    private val media: ChatMediaStore,
) : ViewModel() {

    private val id = MutableStateFlow<Long?>(null)
    private val hasOlder = MutableStateFlow(true)
    private val loadingOlder = MutableStateFlow(false)
    private val replyTo = MutableStateFlow<ChatMessage?>(null)
    private val editing = MutableStateFlow<ChatMessage?>(null)
    private val shared = MutableStateFlow<SharePayload?>(null)
    private val busy = MutableStateFlow(false)
    private val search = MutableStateFlow<List<ChatMessage>?>(null)
    private val searching = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)
    private val now = MutableStateFlow(System.currentTimeMillis())

    private val notices = Channel<ConversationNotice>(Channel.BUFFERED)
    val notice: Flow<ConversationNotice> = notices.receiveAsFlow()

    val recorder = VoiceRecorder(context)
    val player = VoicePlayer()

    private val conversation = id.filterNotNull().flatMapLatest { repository.conversation(it) }
    private val messages = id.flatMapLatest { if (it == null) flowOf(emptyList()) else repository.messages(it) }
    private val outbox = id.flatMapLatest { if (it == null) flowOf(emptyList()) else repository.outbox(it) }

    private data class Ui(val replyTo: ChatMessage?, val editing: ChatMessage?, val shared: SharePayload?, val busy: Boolean)
    private data class Paging(val hasOlder: Boolean, val loadingOlder: Boolean, val loaded: Boolean)

    private val ui = combine(replyTo, editing, shared, busy) { r, e, s, b -> Ui(r, e, s, b) }
    private val paging = combine(hasOlder, loadingOlder, loaded) { h, l, d -> Paging(h, l, d) }
    private val searchState = combine(search, searching) { r, s -> r to s }

    val uiState: StateFlow<ConversationUiState> = combine(
        combine(id, conversation.stateInNull(), messages, outbox) { i, c, m, o -> Quad(i, c, m, o) },
        combine(repository.profile, repository.typing, now) { p, t, n -> Triple(p, t, n) },
        ui,
        paging,
        combine(searchState, repository.dropped) { s, d -> s to d },
    ) { (cid, c, msgs, pend), (profile, typing, nowMs), u, pg, (search, dropped) ->
        val (results, isSearching) = search
        ConversationUiState(
            conversationId = cid,
            conversation = c,
            items = ChatTimeline.build(msgs, pend, c),
            profile = profile,
            typing = if (cid == null) emptyList() else ChatTimeline.typingNow(typing, cid, profile?.user?.userId, nowMs),
            hasOlder = pg.hasOlder,
            loadingOlder = pg.loadingOlder,
            replyTo = u.replyTo,
            editing = u.editing,
            shared = u.shared,
            busy = u.busy,
            searchResults = results,
            searching = isSearching,
            loaded = pg.loaded || msgs.isNotEmpty(),
            gone = cid != null && cid in dropped,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    private data class Quad(val id: Long?, val c: ChatConversation?, val m: List<ChatMessage>, val o: List<com.gatecontrol.android.chat.ChatPending>)

    private fun Flow<ChatConversation?>.stateInNull(): StateFlow<ChatConversation?> = stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                now.value = System.currentTimeMillis()
            }
        }
    }

    /** Shows conversation [conversationId] (idempotent); [share] takes what was shared from another app. */
    fun bind(conversationId: Long, share: Boolean = false) {
        if (share) ChatShareHandover.take()?.let { shared.value = it }
        if (id.value == conversationId) return
        id.value = conversationId
        replyTo.value = null
        editing.value = null
        search.value = null
        loaded.value = false
        viewModelScope.launch {
            hasOlder.value = repository.hasOlder(conversationId)
            if (repository.cachedConversation(conversationId) == null) repository.refreshConversation(conversationId)
            if (repository.profile.value == null) repository.refreshProfile()
            repository.openConversation(conversationId)
            hasOlder.value = repository.hasOlder(conversationId)
            loaded.value = true
        }
    }

    /** Visible on screen (resumed): `active` pings, no notifications for it. */
    fun setVisible(visible: Boolean) {
        val cid = id.value ?: return
        if (visible) {
            repository.opened(cid)
            repository.setActive(cid)
            viewModelScope.launch { repository.markRead(cid) }
        } else if (repository.activeConversation.value == cid) {
            repository.setActive(null)
        }
    }

    /** The newest message is on screen: read. */
    fun onNewestVisible() {
        val cid = id.value ?: return
        if (repository.activeConversation.value != cid) return
        val c = uiState.value.conversation ?: return
        if (c.unread > 0) viewModelScope.launch { repository.markRead(cid) }
    }

    fun loadOlder() {
        val cid = id.value ?: return
        if (loadingOlder.value || !hasOlder.value) return
        loadingOlder.value = true
        viewModelScope.launch {
            when (val r = repository.loadOlder(cid)) {
                is ChatResult.Ok -> hasOlder.value = r.value
                else -> Unit
            }
            loadingOlder.value = false
        }
    }

    fun onTyping(text: String) {
        val cid = id.value ?: return
        if (text.isBlank()) repository.typingStop(cid) else repository.typingPing(cid)
    }

    fun startReply(m: ChatMessage) {
        editing.value = null
        replyTo.value = m
    }

    fun cancelReply() {
        replyTo.value = null
    }

    fun startEdit(m: ChatMessage) {
        replyTo.value = null
        editing.value = m
    }

    fun cancelEdit() {
        editing.value = null
    }

    /** Sends [text] (or saves the edit). Returns true when the composer may be cleared. */
    fun send(text: String): Boolean {
        val cid = id.value ?: return false
        val edit = editing.value
        if (edit != null) {
            if (text.isBlank() && edit.kind == ChatMessage.KIND_TEXT) return false
            editing.value = null
            viewModelScope.launch {
                val r = repository.edit(edit, text)
                if (r !is ChatResult.Ok) notices.trySend(ConversationNotice.Error(codeOf(r)))
            }
            return true
        }
        if (text.isBlank()) return false
        val reply = replyTo.value?.id
        replyTo.value = null
        viewModelScope.launch { repository.sendText(cid, text, reply) }
        return true
    }

    /**
     * Pictures and videos from the photo picker: pictures scaled with a
     * preview, videos as files (with frame, length and size when the
     * server takes them).
     */
    fun sendImages(uris: List<Uri>, caption: String?) {
        val (videos, pictures) = uris.partition { ChatPreview.isVideo(runCatching { context.contentResolver.getType(it) }.getOrNull()) }
        if (videos.isNotEmpty()) sendFiles(videos, if (pictures.isEmpty()) caption else null)
        if (pictures.isNotEmpty()) sendPictures(pictures, caption)
    }

    private fun sendPictures(uris: List<Uri>, caption: String?) {
        val cid = id.value ?: return
        val limits = uiState.value.profile?.limits
        val reply = replyTo.value?.id
        replyTo.value = null
        viewModelScope.launch {
            busy.value = true
            uris.forEachIndexed { i, uri ->
                val clientId = repository.newClientId()
                val img = media.prepareImage(uri, clientId, limits?.imageMaxSide ?: 2048, limits?.thumbMaxBytes ?: 65_536)
                if (img == null) {
                    notices.trySend(ConversationNotice.PrepareFailed(PrepareError.Unreadable))
                } else {
                    val max = limits?.maxBytes
                    if (max != null && img.file.length() > max) {
                        media.dropOutboxFiles(img.file.path, img.thumb?.path)
                        notices.trySend(ConversationNotice.PrepareFailed(PrepareError.TooLarge(max)))
                    } else {
                        repository.sendImage(cid, img, if (i == 0) caption else null, if (i == 0) reply else null, clientId)
                    }
                }
            }
            busy.value = false
        }
    }

    /** A photo taken with the camera app ([path] in `cache/camera`): scaled and sent like a picked one. */
    fun sendCameraPhoto(path: String, caption: String?) {
        val cid = id.value ?: return
        val file = media.cameraFile(path) ?: run {
            notices.trySend(ConversationNotice.PrepareFailed(PrepareError.Unreadable))
            return
        }
        val limits = uiState.value.profile?.limits
        val reply = replyTo.value?.id
        replyTo.value = null
        viewModelScope.launch {
            busy.value = true
            try {
                val clientId = repository.newClientId()
                val img = media.prepareImage(Uri.fromFile(file), clientId, limits?.imageMaxSide ?: 2048, limits?.thumbMaxBytes ?: 65_536)
                val max = limits?.maxBytes
                when {
                    img == null -> notices.trySend(ConversationNotice.PrepareFailed(PrepareError.Unreadable))
                    max != null && img.file.length() > max -> {
                        media.dropOutboxFiles(img.file.path, img.thumb?.path)
                        notices.trySend(ConversationNotice.PrepareFailed(PrepareError.TooLarge(max)))
                    }
                    else -> repository.sendImage(cid, img, caption, reply, clientId)
                }
            } finally {
                // The full-size original is not kept: the scaled copy is in the outbox.
                file.delete()
                busy.value = false
            }
        }
    }

    /** A new target file for the camera app and its content URI. */
    fun newCameraTarget(): Pair<File, Uri>? = runCatching { media.newCameraTarget() }.getOrNull()

    /**
     * Any file (SAF, videos of the photo picker, shared files); pictures
     * picked as files are sent as files. Videos get a frame, their length
     * and size, PDFs their first page as preview — only for servers that
     * take them (`file_thumbs`, `video_meta`; older ones get the file alone).
     */
    fun sendFiles(uris: List<Uri>, caption: String? = null) {
        val cid = id.value ?: return
        val profile = uiState.value.profile
        val limits = profile?.limits
        val features = profile?.features
        val reply = replyTo.value?.id
        replyTo.value = null
        viewModelScope.launch {
            busy.value = true
            uris.forEachIndexed { i, uri ->
                val clientId = repository.newClientId()
                media.prepareFile(uri, clientId, limits?.maxBytes, limits?.types.orEmpty())
                    .onSuccess { f ->
                        val file = media.withPreview(
                            f, clientId,
                            thumbs = ChatPreview.wantsThumb(f.mime, features),
                            videoMeta = features?.videoMeta == true,
                            thumbMax = limits?.thumbMaxBytes ?: 65_536,
                            maxSide = limits?.thumbMaxSide ?: ChatPreview.THUMB_MAX_SIDE,
                        )
                        repository.sendFile(cid, file, if (i == 0) caption else null, if (i == 0) reply else null, clientId)
                    }
                    .onFailure { e -> notices.trySend(ConversationNotice.PrepareFailed((e as? PrepareException)?.error ?: PrepareError.Unreadable)) }
            }
            busy.value = false
        }
    }

    /** What was shared from another app: pictures as pictures, the rest as files, the text into the composer. */
    fun sendShared() {
        val payload = shared.value ?: return
        shared.value = null
        val files = payload.files
        val (images, others) = files.partition { ChatMime.kindOf(it.mime) == ChatMessage.KIND_IMAGE }
        if (images.isNotEmpty()) sendPictures(images.map { Uri.fromFile(File(it.path)) }, null)
        if (others.isNotEmpty()) sendFiles(others.map { Uri.fromFile(File(it.path)) })
    }

    fun dropShared() {
        shared.value?.files?.forEach { File(it.path).delete() }
        shared.value = null
    }

    // ─── voice ──────────────────────────────────────────────────────

    private var voiceClientId: String? = null

    fun startRecording(): Boolean {
        if (id.value == null) return false
        val clientId = repository.newClientId()
        voiceClientId = clientId
        val maxMs = ((uiState.value.profile?.limits?.maxVoiceS ?: 120) * 1000L)
        val ok = recorder.start(File(media.outboxDir, "$clientId.m4a"), maxMs) { finishRecording() }
        if (!ok) notices.trySend(ConversationNotice.RecordingFailed)
        return ok
    }

    fun finishRecording() {
        val cid = id.value ?: return
        val take = recorder.finish() ?: return
        val clientId = voiceClientId ?: return
        voiceClientId = null
        val reply = replyTo.value?.id
        replyTo.value = null
        viewModelScope.launch { repository.sendVoice(cid, take.file, take.mime, take.durationMs, take.waveform, reply, clientId) }
    }

    fun cancelRecording() {
        recorder.cancel()
        voiceClientId = null
    }

    /** Plays (or pauses) the voice message [m]. */
    fun togglePlay(m: ChatMessage) {
        val mediaId = m.media?.id ?: return
        viewModelScope.launch {
            val f = media.file(mediaId)
            if (f == null) notices.trySend(ConversationNotice.Error("offline")) else player.toggle("m:${m.id}", f)
        }
    }

    fun togglePlayLocal(clientId: String, path: String) {
        player.toggle("c:$clientId", File(path))
    }

    // ─── location ───────────────────────────────────────────────────

    /** One-off position as a message (after the permission was granted). */
    fun sendLocation() {
        val cid = id.value ?: return
        val reply = replyTo.value?.id
        viewModelScope.launch {
            busy.value = true
            when (val r = DeviceLocator.locate(context)) {
                is LocateResult.Fix -> {
                    replyTo.value = null
                    val loc = r.location
                    val label = runCatching { FamilieGeocoder.address(context, loc.latitude, loc.longitude) }.getOrNull()
                    repository.sendLocation(
                        cid,
                        ChatLocation(loc.latitude, loc.longitude, label, if (loc.hasAccuracy()) loc.accuracy.roundToInt() else null),
                        reply,
                    )
                }
                else -> notices.trySend(ConversationNotice.Location(r))
            }
            busy.value = false
        }
    }

    // ─── message actions ────────────────────────────────────────────

    fun react(m: ChatMessage, emoji: String) {
        val mine = m.reactions.firstOrNull { r -> uiState.value.myId?.let { it in r.userIds } == true }?.emoji
        viewModelScope.launch {
            val r = repository.react(m, if (mine == emoji) null else emoji)
            if (r !is ChatResult.Ok) notices.trySend(ConversationNotice.Error(codeOf(r)))
        }
    }

    fun delete(m: ChatMessage, forAll: Boolean) {
        viewModelScope.launch {
            val r = repository.delete(m, forAll)
            if (r !is ChatResult.Ok) notices.trySend(ConversationNotice.Error(codeOf(r)))
        }
    }

    fun retry(clientId: String) = repository.retry(clientId)

    fun discard(clientId: String) = repository.discard(clientId)

    /** The file of [m], downloaded (for opening, sharing, the picture viewer). */
    suspend fun file(m: ChatMessage): File? = m.media?.id?.let { media.file(it) }

    /** The caller left this conversation on this device (no "no longer available" notice). */
    fun leftHere(): Boolean = id.value?.let { repository.leftHere(it) } == true

    fun canEdit(m: ChatMessage): Boolean {
        val p = uiState.value.profile ?: return false
        return p.features.edit && ChatTimeline.canEdit(m, p.limits.editWindowS, System.currentTimeMillis())
    }

    fun canDeleteForAll(m: ChatMessage): Boolean {
        val p = uiState.value.profile ?: return false
        return ChatTimeline.withinWindow(m, p.limits.editWindowS, System.currentTimeMillis())
    }

    // ─── search ─────────────────────────────────────────────────────

    fun search(q: String) {
        val cid = id.value ?: return
        val query = q.trim()
        if (query.length < 2) {
            search.value = null
            return
        }
        searching.value = true
        viewModelScope.launch {
            when (val r = repository.search(cid, query)) {
                is ChatResult.Ok -> search.value = r.value.results
                else -> {
                    search.value = emptyList()
                    notices.trySend(ConversationNotice.Error(codeOf(r)))
                }
            }
            searching.value = false
        }
    }

    fun closeSearch() {
        search.value = null
    }

    private fun codeOf(r: ChatResult<*>): String? = when (r) {
        is ChatResult.Failed -> r.code
        ChatResult.Offline -> "offline"
        else -> null
    }

    override fun onCleared() {
        recorder.cancel()
        player.stop()
        id.value?.let { if (repository.activeConversation.value == it) repository.setActive(null) }
    }
}
