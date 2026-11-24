package com.gatecontrol.android.tv.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.common.ContinueLink
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MAi
import com.gatecontrol.android.mail.MAttachment
import com.gatecontrol.android.mail.MDraft
import com.gatecontrol.android.mail.MMessage
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailBulk
import com.gatecontrol.android.tv.input.TvComposerKit
import com.gatecontrol.android.tv.input.TvHandoff
import com.gatecontrol.android.tv.input.TvSnippetSource
import com.gatecontrol.android.tv.zuhause.TvSourceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Answering a mail: the server makes the draft, a forward picks whom, then the composer. */
sealed interface TvMailCompose {
    data object Closed : TvMailCompose

    /** `POST drafts` asked. */
    data class Preparing(val mode: TvMailMode, val messageId: Long) : TvMailCompose

    /** A forward: whom to (people the plugin knows, [targets] null while they load). */
    data class Picking(val draft: MDraft, val messageId: Long, val targets: List<MAddress>?, val query: String = "") : TvMailCompose

    /** The composer is open; [initial]: the text it starts with (after „Rückgängig“ the one sent). */
    data class Writing(
        val mode: TvMailMode,
        val messageId: Long,
        val draft: MDraft,
        val to: List<MAddress>?,
        val initial: String = "",
    ) : TvMailCompose
}

/** Sent: „Rückgängig“ until the server's window ends. */
data class TvMailSent(val outboxId: Long, val draftId: Long, val mode: TvMailMode, val messageId: Long, val to: List<MAddress>?, val text: String)

/** Archived or deleted: „Rückgängig“ with the server's token. */
data class TvMailMovedUi(val id: Long, val action: MailBulk.Action, val token: String)

/** A message at the bottom of the page for a few seconds. */
enum class TvMailMessage { SENT, ARCHIVED, DELETED, BACK, SEND_STOPPED, TOO_LATE, REFUSED, OFFLINE }

/** What the page „Mail“ shows. */
data class TvMailUi(
    /** `GET me`: whether Mail is there for this person. */
    val meState: TvSourceState = TvSourceState.LOADING,
    /** The accounts chosen on the TV (empty: the hint to choose in Einstellungen › Mail). */
    val accounts: List<MAccount> = emptyList(),
    /** All the person's accounts (Einstellungen › Mail) and the ids chosen of them. */
    val allAccounts: List<MAccount> = emptyList(),
    val chosen: Set<Long> = emptySet(),
    val items: List<MSummary> = emptyList(),
    val listState: TvSourceState = TvSourceState.LOADING,
    val selectedId: Long? = null,
    val message: MMessage? = null,
    val messageState: TvSourceState = TvSourceState.LOADING,
    /** „Kurz gesagt“ and the answer suggestions (null: none, or the AI is off). */
    val ai: MAi? = null,
    val aiOn: Boolean = false,
    val compose: TvMailCompose = TvMailCompose.Closed,
    val sent: TvMailSent? = null,
    val moved: TvMailMovedUi? = null,
    val note: TvMailMessage? = null,
) {
    val unread: Int get() = TvMailLogic.unread(items)
}

/** The page „Mail“ (stage 3f): the list on the left, the chosen mail on the right, answering with the composer. */
@HiltViewModel
class TvMailViewModel @Inject constructor(
    private val mail: TvMail,
    private val settings: TvMailSettings,
    private val kit: TvComposerKit,
) : ViewModel() {

    private data class Reader(
        val selectedId: Long? = null,
        val message: MMessage? = null,
        val state: TvSourceState = TvSourceState.LOADING,
        val ai: MAi? = null,
    )

    private data class Extras(
        val compose: TvMailCompose = TvMailCompose.Closed,
        val sent: TvMailSent? = null,
        val moved: TvMailMovedUi? = null,
        val note: TvMailMessage? = null,
    )

    private val _reader = MutableStateFlow(Reader())
    private val _extras = MutableStateFlow(Extras())
    private var readJob: Job? = null
    private var sentJob: Job? = null
    private var movedJob: Job? = null
    private var noteJob: Job? = null

    /** The draft went to „Am Handy weiterschreiben“: closing the composer keeps it on the server. */
    private var handedOff = false

    val ui: StateFlow<TvMailUi> = combine(mail.data, settings.accounts, _reader, _extras) { d, chosen, r, x ->
        val accounts = TvMailLogic.accounts(d.me, chosen)
        val states = accounts.map { d.listStates[it.id] ?: TvSourceState.LOADING }
        TvMailUi(
            meState = d.meState,
            accounts = accounts,
            allAccounts = d.me?.accounts.orEmpty(),
            chosen = chosen,
            items = TvMailLogic.inbox(d.lists, chosen),
            listState = when {
                states.isEmpty() -> d.meState
                states.any { it == TvSourceState.OK } -> TvSourceState.OK
                states.all { it == TvSourceState.OFFLINE } -> TvSourceState.OFFLINE
                states.any { it == TvSourceState.LOADING } -> TvSourceState.LOADING
                else -> TvSourceState.MISSING
            },
            selectedId = r.selectedId,
            message = r.message?.takeIf { it.id == r.selectedId },
            messageState = r.state,
            ai = r.ai,
            aiOn = r.message?.let { TvMailLogic.aiOn(d.me, it.summary.accountId) } ?: false,
            compose = x.compose,
            sent = x.sent,
            moved = x.moved,
            note = x.note,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvMailUi())

    /** The page shows: `GET me` and the inboxes (at most every half minute unless [force]d). */
    fun refresh(force: Boolean = false) = mail.refreshAsync(force)

    /** Einstellungen › Mail: an account shows on the TV or not; the inboxes load again. */
    fun setAccount(id: Long, on: Boolean) {
        settings.setAccount(id, on)
        if (!on && mail.data.value.lists.isNotEmpty() && _reader.value.message?.summary?.accountId == id) {
            readJob?.cancel()
            _reader.value = Reader()
        }
        mail.refreshAsync(force = true)
    }

    /**
     * A mail of the list is chosen (the focus rests on it): it loads on the right; once shown for
     * [TvMailLogic.READ_AFTER_MS] it counts as read and the AI is asked to make „Kurz gesagt“ when it has none yet.
     */
    fun select(summary: MSummary) {
        if (_reader.value.selectedId == summary.id && _reader.value.message != null) return
        readJob?.cancel()
        _reader.value = Reader(selectedId = summary.id)
        readJob = viewModelScope.launch {
            val r = mail.message(summary.id)
            if (r !is MResult.Ok) {
                _reader.update { if (it.selectedId == summary.id) it.copy(state = mailState(r)) else it }
                return@launch
            }
            val m = r.value
            _reader.update { if (it.selectedId == summary.id) it.copy(message = m, state = TvSourceState.OK) else it }
            val aiOn = TvMailLogic.aiOn(mail.data.value.me, m.summary.accountId)
            // the AI's result that is there, beside the 3 s (they never wait for each other)
            if (aiOn) {
                launch {
                    val ai = mail.ai(m.id)
                    _reader.update { if (it.selectedId == m.id && it.ai == null) it.copy(ai = ai) else it }
                }
            }
            delay(TvMailLogic.READ_AFTER_MS)
            if (_reader.value.selectedId != m.id) return@launch
            if (m.summary.unread || mail.data.value.lists.values.any { l -> l.any { it.id == m.id && it.unread } }) {
                mail.markRead(m.id)
                _reader.update { if (it.selectedId == m.id) it.copy(message = it.message?.let { x -> x.copy(summary = x.summary.copy(unread = false)) }) else it }
            }
            // none yet: made now, only for a mail really read (the AI allows a few an hour)
            if (aiOn && _reader.value.ai == null) {
                val ai = mail.ai(m.id, run = true)
                _reader.update { if (it.selectedId == m.id && it.ai == null) it.copy(ai = ai) else it }
            }
        }
    }

    // ─── archive, delete ────────────────────────────────────────────

    fun archive(id: Long) = move(id, MailBulk.Action.ARCHIVE)

    fun delete(id: Long) = move(id, MailBulk.Action.DELETE)

    private fun move(id: Long, action: MailBulk.Action) {
        val next = nextAfter(id)
        viewModelScope.launch {
            val r = mail.move(id, action)
            if (r.done != TvMailDone.OK) {
                report(r.done)
                return@launch
            }
            if (_reader.value.selectedId == id) {
                readJob?.cancel()
                if (next != null) select(next) else _reader.value = Reader()
            }
            movedJob?.cancel()
            val token = r.undo?.token
            _extras.update { it.copy(moved = token?.let { t -> TvMailMovedUi(id, action, t) }) }
            say(if (action == MailBulk.Action.ARCHIVE) TvMailMessage.ARCHIVED else TvMailMessage.DELETED)
            if (token != null) {
                movedJob = viewModelScope.launch {
                    delay(UNDO_MS)
                    _extras.update { x -> if (x.moved?.token == token) x.copy(moved = null) else x }
                }
            }
        }
    }

    fun undoMove() {
        val m = _extras.value.moved ?: return
        movedJob?.cancel()
        _extras.update { it.copy(moved = null) }
        viewModelScope.launch {
            val r = mail.undoMove(m.token)
            if (r == TvMailDone.OK) say(TvMailMessage.BACK) else report(r)
        }
    }

    private fun nextAfter(id: Long): MSummary? {
        val items = ui.value.items.ifEmpty { TvMailLogic.inbox(mail.data.value.lists, settings.accounts.value) }
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return null
        return items.getOrNull(i + 1) ?: items.getOrNull(i - 1)
    }

    // ─── answering ──────────────────────────────────────────────────

    /** „Antworten“, „Allen antworten“, „Weiterleiten“ on the mail shown. */
    fun answer(mode: TvMailMode, messageId: Long) {
        if (_extras.value.compose != TvMailCompose.Closed) return
        handedOff = false
        _extras.update { it.copy(compose = TvMailCompose.Preparing(mode, messageId)) }
        viewModelScope.launch {
            when (val r = mail.startDraft(mode, messageId)) {
                is MResult.Ok -> {
                    if (mode == TvMailMode.FORWARD) {
                        _extras.update { it.copy(compose = TvMailCompose.Picking(r.value, messageId, null)) }
                        search("")
                    } else {
                        _extras.update { it.copy(compose = TvMailCompose.Writing(mode, messageId, r.value, null, TvMailLogic.written(r.value))) }
                    }
                }
                else -> {
                    _extras.update { it.copy(compose = TvMailCompose.Closed) }
                    report(mailOutcome(r))
                }
            }
        }
    }

    /** The people a forward can go to, by name ([query] empty: those written with lately). */
    fun search(query: String) {
        val p = _extras.value.compose as? TvMailCompose.Picking ?: return
        _extras.update { it.copy(compose = p.copy(targets = null, query = query)) }
        viewModelScope.launch {
            val found = mail.forwardTargets(query).orEmpty()
            _extras.update { x ->
                val now = x.compose as? TvMailCompose.Picking
                if (now != null && now.draft.id == p.draft.id && now.query == query) x.copy(compose = now.copy(targets = found)) else x
            }
        }
    }

    fun pick(to: MAddress) {
        val p = _extras.value.compose as? TvMailCompose.Picking ?: return
        _extras.update { it.copy(compose = TvMailCompose.Writing(TvMailMode.FORWARD, p.messageId, p.draft, listOf(to), TvMailLogic.written(p.draft))) }
    }

    /**
     * „Senden“ in the composer's preview: the text into the draft, then `send`; true: sent, „Rückgängig“ for the
     * server's window (10 s); false: „Nicht gesendet“, the draft stays.
     */
    suspend fun send(text: String): Boolean {
        val w = _extras.value.compose as? TvMailCompose.Writing ?: return false
        val r = mail.send(w.draft, w.mode, w.messageId, text, w.to)
        if (r !is MResult.Ok) {
            if (r is MResult.Offline || r is MResult.NotConfigured) say(TvMailMessage.OFFLINE)
            return false
        }
        val sent = TvMailSent(r.value.send.outboxId, w.draft.id, w.mode, w.messageId, w.to, text)
        sentJob?.cancel()
        _extras.update { it.copy(compose = TvMailCompose.Closed, sent = sent) }
        sentJob = viewModelScope.launch {
            delay(r.value.windowMs)
            _extras.update { x -> if (x.sent?.outboxId == sent.outboxId) x.copy(sent = null) else x }
            say(TvMailMessage.SENT)
        }
        return true
    }

    /** „Rückgängig“ after „Senden“: the send stops and the composer opens again with the text. */
    fun undoSend() {
        val s = _extras.value.sent ?: return
        sentJob?.cancel()
        _extras.update { it.copy(sent = null) }
        viewModelScope.launch {
            when (val r = mail.cancelSend(s.outboxId)) {
                is MResult.Ok -> {
                    val draftId = r.value ?: s.draftId
                    val draft = (mail.draft(draftId) as? MResult.Ok)?.value
                    if (draft == null) {
                        say(TvMailMessage.SEND_STOPPED)
                        return@launch
                    }
                    handedOff = false
                    _extras.update { it.copy(compose = TvMailCompose.Writing(s.mode, s.messageId, draft, s.to, s.text)) }
                    say(TvMailMessage.SEND_STOPPED)
                }
                is MResult.Failed -> say(TvMailMessage.TOO_LATE)
                else -> say(TvMailMessage.OFFLINE)
            }
        }
    }

    /**
     * The composer closed without sending (Verwerfen, Zurück): the draft goes from the server — unless it went to
     * „Am Handy weiterschreiben“, then it stays for the phone.
     */
    fun closeCompose() {
        val c = _extras.value.compose
        _extras.update { it.copy(compose = TvMailCompose.Closed) }
        val draftId = when (c) {
            is TvMailCompose.Writing -> c.draft.id
            is TvMailCompose.Picking -> c.draft.id
            else -> null
        }
        if (draftId != null && !handedOff) viewModelScope.launch { mail.discard(draftId) }
    }

    /** The building blocks of the composer: the AI's answers to this mail, then the own ones. */
    fun snippetSources(messageId: Long): List<TvSnippetSource> = listOf(
        TvSnippetSource {
            val ai = _reader.value.ai?.takeIf { _reader.value.selectedId == messageId }
                ?: if (TvMailLogic.aiOn(mail.data.value.me, _reader.value.message?.summary?.accountId ?: 0)) mail.ai(messageId) else null
            TvMailLogic.aiSnippets(ai)
        },
        kit.ownSnippets,
    )

    /** „Am Handy weiterschreiben“: the text into the draft first, then the link to it as a GateDrop text. */
    fun handoff(w: TvMailCompose.Writing): TvHandoff {
        val base = kit.handoff(ContinueLink.MailDraft(w.draft.id))
        return TvHandoff(base.targets) { to, text ->
            mail.save(w.draft, w.mode, w.messageId, text, w.to)
            handedOff = true
            base.send(to, text)
        }
    }

    // ─── attachments and the HTML ───────────────────────────────────

    suspend fun attachment(messageId: Long, a: MAttachment): File? = mail.attachment(messageId, a)

    suspend fun picture(path: String): Pair<ByteArray, String>? = mail.picture(path)

    private fun report(r: TvMailDone) {
        when (r) {
            TvMailDone.OK -> Unit
            TvMailDone.REFUSED -> say(TvMailMessage.REFUSED)
            TvMailDone.OFFLINE -> say(TvMailMessage.OFFLINE)
        }
    }

    private fun say(m: TvMailMessage) {
        noteJob?.cancel()
        _extras.update { it.copy(note = m) }
        noteJob = viewModelScope.launch {
            delay(MESSAGE_MS)
            _extras.update { x -> if (x.note == m) x.copy(note = null) else x }
        }
    }

    private companion object {
        const val UNDO_MS = 10_000L
        const val MESSAGE_MS = 4_000L
    }
}
