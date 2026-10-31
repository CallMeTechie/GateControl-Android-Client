package com.gatecontrol.android.ui.mail

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MAtt
import com.gatecontrol.android.mail.MContact
import com.gatecontrol.android.mail.MDraft
import com.gatecontrol.android.mail.MDraftAttachment
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MWarning
import com.gatecontrol.android.mail.MailCache
import com.gatecontrol.android.mail.MailJson
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.mail.MailRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** An attachment of the mail being written, as the screen shows it. */
sealed class ComposeAttachment {
    abstract val key: String
    abstract val name: String
    abstract val size: Long

    /** On the draft already (the server knows it). */
    data class Saved(val a: MDraftAttachment) : ComposeAttachment() {
        override val key get() = "s${a.id}"
        override val name get() = a.name
        override val size get() = a.size
    }

    /** Added here, goes with the next save. */
    data class Pending(override val key: String, val att: MAtt, override val name: String, override val size: Long, val mime: String) : ComposeAttachment()

    /** A device file on its way to the server. */
    data class Uploading(override val key: String, override val name: String, override val size: Long) : ComposeAttachment()
}

/** The recipient fields. */
enum class RecipientField { TO, CC, BCC }

data class ComposeState(
    val loading: Boolean = true,
    val accountId: Long? = null,
    val to: List<MAddress> = emptyList(),
    val cc: List<MAddress> = emptyList(),
    val bcc: List<MAddress> = emptyList(),
    val inputs: Map<RecipientField, String> = emptyMap(),
    val showCc: Boolean = false,
    val subject: String = "",
    val text: String = "",
    /** The quote below (answer / forward): the server's HTML, sent unchanged. */
    val quote: String = "",
    val attachments: List<ComposeAttachment> = emptyList(),
    val warnings: List<MWarning> = emptyList(),
    val draftId: Long? = null,
    val saving: Boolean = false,
    val sending: Boolean = false,
    val suggestions: List<MContact> = emptyList(),
    val suggestFor: RecipientField? = null,
    val error: String? = null,
) {
    fun recipients(f: RecipientField): List<MAddress> = when (f) {
        RecipientField.TO -> to
        RecipientField.CC -> cc
        RecipientField.BCC -> bcc
    }

    val empty: Boolean
        get() = to.isEmpty() && cc.isEmpty() && bcc.isEmpty() && subject.isBlank() && attachments.isEmpty() &&
            inputs.values.all { it.isBlank() } && text.isBlank()
}

sealed class ComposeEvent {
    data object Sent : ComposeEvent()
    data object Saved : ComposeEvent()
    data object Discarded : ComposeEvent()
    data class Toast(val text: String) : ComposeEvent()
}

/**
 * Writing (mockup "Schreiben"): a new mail, an answer or a forward (the server fills recipients, subject and quote
 * in), or a draft again. Saved as a draft on the server while writing; sent with the undo window, or planned.
 */
@HiltViewModel
class MailComposeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me

    private val argDraft = saved.get<String>(Screen.MailCompose.ARG_DRAFT)?.toLongOrNull()
    private val argRef = saved.get<String>(Screen.MailCompose.ARG_REF)?.toLongOrNull()
    private val argMode = saved.get<String>(Screen.MailCompose.ARG_MODE)?.takeIf { it in MailJson.MODES }

    /** reply | replyAll | forward | null (a new mail or a draft). */
    val mode: String? get() = argMode
    private val argAccount = saved.get<String>(Screen.MailCompose.ARG_ACCOUNT)?.toLongOrNull()
    private val argTo = saved.get<String>(Screen.MailCompose.ARG_TO)
    private val argReply = saved.get<String>(Screen.MailCompose.ARG_REPLY)

    private val _state = MutableStateFlow(ComposeState())
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    private val _event = MutableStateFlow<ComposeEvent?>(null)
    val event: StateFlow<ComposeEvent?> = _event.asStateFlow()

    private val saveMutex = Mutex()
    private var autosave: Job? = null
    private var suggestJob: Job? = null
    /** Something changed since the last save. */
    @Volatile private var dirty = false
    /** The person changed something here (else closing leaves nothing behind). */
    @Volatile private var touched = argReply != null

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val me = repository.me.value ?: repository.refresh().valueOrNull()
        when {
            argDraft != null -> when (val r = api.draft(argDraft)) {
                is MResult.Ok -> fromDraft(r.value)
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
            argRef != null && argMode != null -> {
                // the server fills recipients, subject and quote in (always from the account the mail came to)
                when (val r = api.createDraft(MailJson.draftBody(null, null, null, null, null, null, replyTo = argRef, mode = argMode))) {
                    is MResult.Ok -> {
                        fromDraft(r.value)
                        val account = me?.account(r.value.accountId)
                        _state.update { s -> s.copy(text = MailLogic.withSignature(MailLogic.withReply(argReply.orEmpty(), s.text), account?.signature).trimStart('\n')) }
                        if (argMode == "forward" && r.value.attachments.isEmpty()) forwardAttachments(argRef)
                    }
                    else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
                }
            }
            else -> {
                val accountId = argAccount?.takeIf { a -> me?.account(a) != null } ?: me?.accounts?.firstOrNull()?.id
                val to = argTo?.let { MailLogic.parseRecipients(it).addresses }.orEmpty()
                _state.update {
                    it.copy(
                        loading = false,
                        accountId = accountId,
                        to = to,
                        text = MailLogic.withSignature("", me?.account(accountId)?.signature),
                    )
                }
            }
        }
    }

    private fun fromDraft(d: MDraft) {
        val (text, quote) = MailLogic.splitDraft(d.html)
        _state.update {
            it.copy(
                loading = false,
                draftId = d.id,
                accountId = d.accountId.takeIf { a -> a > 0 } ?: it.accountId,
                to = d.to,
                cc = d.cc,
                bcc = d.bcc,
                showCc = d.cc.isNotEmpty() || d.bcc.isNotEmpty(),
                subject = d.subject,
                text = text,
                quote = quote,
                attachments = d.attachments.map { a -> ComposeAttachment.Saved(a) },
                warnings = d.warnings,
            )
        }
    }

    /** Forwarding: the mail's own attachments go along (contract: `{ messageId, attachmentId }`). */
    private suspend fun forwardAttachments(ref: Long) {
        val m = api.message(ref).valueOrNull()?.first ?: repository.cache.message(ref) ?: return
        val atts = m.attachments.filter { !it.inline }
        if (atts.isEmpty()) return
        _state.update { s ->
            s.copy(attachments = s.attachments + atts.map { a -> ComposeAttachment.Pending("f${a.id}", MAtt.Forwarded(ref, a.id), a.name, a.size, a.mime) })
        }
        save()
    }

    // ─── editing ────────────────────────────────────────────────────

    private fun changed() {
        dirty = true
        touched = true
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            if (!_state.value.empty) save()
        }
    }

    fun setAccount(id: Long) {
        _state.update { s ->
            val old = me.value?.account(s.accountId)?.signature
            val new = me.value?.account(id)?.signature
            // the signature follows the account while it is still the untouched one
            val text = if (old != null && s.text.contains(old)) s.text.replace(old, new.orEmpty()) else s.text
            s.copy(accountId = id, text = text)
        }
        changed()
    }

    fun setSubject(v: String) {
        _state.update { it.copy(subject = v) }
        changed()
    }

    fun setText(v: String) {
        _state.update { it.copy(text = v) }
        changed()
    }

    fun showCc() = _state.update { it.copy(showCc = true) }

    /** Typing in a recipient field: complete addresses become chips, the rest asks for suggestions. */
    fun setInput(f: RecipientField, v: String) {
        val ends = v.endsWith(",") || v.endsWith(";") || v.endsWith("\n")
        if (ends) {
            commit(f, v)
            return
        }
        _state.update { it.copy(inputs = it.inputs + (f to v)) }
        suggestJob?.cancel()
        val q = v.trim()
        if (q.length < 2) {
            _state.update { it.copy(suggestions = emptyList(), suggestFor = null) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            val found = api.contacts(q).valueOrNull().orEmpty()
            val taken = _state.value.recipients(f).map { it.email.lowercase() }.toSet()
            _state.update { it.copy(suggestions = found.filter { c -> c.email.lowercase() !in taken }, suggestFor = f) }
        }
    }

    /** What was typed into [f] becomes recipients (when it is addresses). */
    fun commit(f: RecipientField, raw: String? = null) {
        val input = raw ?: _state.value.inputs[f].orEmpty()
        val parsed = MailLogic.parseRecipients(input)
        _state.update { s ->
            val list = (s.recipients(f) + parsed.addresses).distinctBy { it.email.lowercase() }
            val withList = when (f) {
                RecipientField.TO -> s.copy(to = list)
                RecipientField.CC -> s.copy(cc = list)
                RecipientField.BCC -> s.copy(bcc = list)
            }
            withList.copy(inputs = s.inputs + (f to parsed.rest), suggestions = if (parsed.addresses.isNotEmpty()) emptyList() else s.suggestions)
        }
        if (parsed.addresses.isNotEmpty()) changed()
    }

    /** A suggestion picked: its address, or a group's members (since Mail 1.2.0). */
    fun pick(f: RecipientField, c: MContact) {
        _state.update { s ->
            val add = if (c.group) c.members else listOf(MAddress(c.name, c.email))
            val list = (s.recipients(f) + add).distinctBy { it.email.lowercase() }
            val next = when (f) {
                RecipientField.TO -> s.copy(to = list)
                RecipientField.CC -> s.copy(cc = list)
                RecipientField.BCC -> s.copy(bcc = list)
            }
            next.copy(inputs = s.inputs + (f to ""), suggestions = emptyList(), suggestFor = null)
        }
        changed()
    }

    fun remove(f: RecipientField, a: MAddress) {
        _state.update { s ->
            when (f) {
                RecipientField.TO -> s.copy(to = s.to - a)
                RecipientField.CC -> s.copy(cc = s.cc - a)
                RecipientField.BCC -> s.copy(bcc = s.bcc - a)
            }
        }
        changed()
    }

    fun removeAttachment(a: ComposeAttachment) {
        _state.update { s -> s.copy(attachments = s.attachments.filter { it.key != a.key }) }
        changed()
    }

    // ─── attachments ────────────────────────────────────────────────

    /** Files of the device: copied, uploaded to the plugin, then on the draft. Too large ones are refused here. */
    fun addFiles(uris: List<Uri>) {
        uris.forEach { uri ->
            val key = "u" + UUID.randomUUID().toString()
            viewModelScope.launch {
                val meta = withContext(Dispatchers.IO) { meta(uri) }
                if (meta.second > MailLogic.ATTACH_MAX_BYTES) {
                    _event.value = ComposeEvent.Toast(context.getString(R.string.mail_attach_too_large, meta.first))
                    return@launch
                }
                touched = true
                _state.update { it.copy(attachments = it.attachments + ComposeAttachment.Uploading(key, meta.first, meta.second)) }
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val file = withContext(Dispatchers.IO) { copy(uri) }
                val r = if (file == null) MResult.Failed("failed", 0) else api.upload(file, mime)
                file?.delete()
                when (r) {
                    is MResult.Ok -> _state.update { s ->
                        s.copy(attachments = s.attachments.map { if (it.key == key) ComposeAttachment.Pending(key, MAtt.Upload(r.value, meta.first, mime), meta.first, meta.second, mime) else it })
                    }
                    else -> {
                        _state.update { s -> s.copy(attachments = s.attachments.filter { it.key != key }) }
                        _event.value = ComposeEvent.Toast(mailText(context, MailHomeViewModel.code(r)))
                    }
                }
                if (r is MResult.Ok) save()
            }
        }
    }

    /** Drive files picked in "Aus Drive" (a copy or a link each). */
    fun addDrive(picks: List<MailDrivePick>) {
        touched = true
        _state.update { s ->
            s.copy(
                attachments = s.attachments + picks.map { p ->
                    ComposeAttachment.Pending("d${p.nodeId}-${UUID.randomUUID()}", MAtt.Drive(p.nodeId, p.asLink, if (p.asLink) MailDrivePick.LINK_DAYS else null), p.name, p.size, p.mime)
                },
            )
        }
        viewModelScope.launch { save() }
    }

    private fun meta(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return MailLogic.fileName(name ?: uri.lastPathSegment) to size
    }

    private fun copy(uri: Uri): File? = runCatching {
        val dir = File(context.cacheDir, MailCache.UPLOAD_DIR).apply { mkdirs() }
        val f = File(dir, UUID.randomUUID().toString())
        context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return null
        if (f.length() > MailLogic.ATTACH_MAX_BYTES) {
            f.delete()
            return null
        }
        f
    }.getOrNull()

    // ─── saving and sending ─────────────────────────────────────────

    private fun body(s: ComposeState): com.google.gson.JsonObject = MailJson.draftBody(
        accountId = s.accountId,
        to = s.to,
        cc = s.cc,
        bcc = s.bcc,
        subject = s.subject,
        html = MailLogic.composeHtml(s.text, s.quote),
        replyTo = argRef,
        mode = argMode,
        attachments = s.attachments.mapNotNull { a ->
            when (a) {
                is ComposeAttachment.Saved -> MAtt.Kept(a.a.id)
                is ComposeAttachment.Pending -> a.att
                is ComposeAttachment.Uploading -> null
            }
        },
    )

    /** The draft on the server now (made the first time). */
    suspend fun save(): MResult<MDraft> = saveMutex.withLock {
        autosave?.cancel()
        // what is still typed in a recipient field counts
        RecipientField.entries.forEach { f -> if (MailLogic.parseRecipients(_state.value.inputs[f].orEmpty()).addresses.isNotEmpty()) commit(f) }
        val s = _state.value
        _state.update { it.copy(saving = true) }
        dirty = false
        val r = s.draftId?.let { api.updateDraft(it, body(s)) } ?: api.createDraft(body(s))
        when (r) {
            is MResult.Ok -> _state.update { now ->
                // attachments added meanwhile stay pending
                val sentKeys = s.attachments.map { it.key }.toSet()
                val added = now.attachments.filter { it.key !in sentKeys }
                now.copy(
                    saving = false,
                    draftId = r.value.id,
                    attachments = r.value.attachments.map { ComposeAttachment.Saved(it) } + added,
                    warnings = r.value.warnings,
                    error = null,
                )
            }
            else -> {
                dirty = true
                _state.update { it.copy(saving = false, error = MailHomeViewModel.code(r)) }
            }
        }
        r
    }

    /** Sends now (with the undo window) or at [sendAt]. */
    fun send(sendAt: Long? = null) {
        if (_state.value.sending) return
        RecipientField.entries.forEach { commit(it) }
        val s = _state.value
        if (s.to.isEmpty() && s.cc.isEmpty() && s.bcc.isEmpty()) {
            _event.value = ComposeEvent.Toast(context.getString(R.string.mail_no_recipient))
            return
        }
        if (s.attachments.any { it is ComposeAttachment.Uploading }) {
            _event.value = ComposeEvent.Toast(context.getString(R.string.mail_wait_upload))
            return
        }
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            val saved = save()
            if (saved !is MResult.Ok) {
                _state.update { it.copy(sending = false) }
                _event.value = ComposeEvent.Toast(mailText(context, MailHomeViewModel.code(saved)))
                return@launch
            }
            val started = SystemClock.elapsedRealtime()
            when (val r = api.send(saved.value.id, sendAt)) {
                is MResult.Ok -> {
                    repository.startUndo(r.value, saved.value.id, SystemClock.elapsedRealtime() - started)
                    _event.value = ComposeEvent.Sent
                }
                else -> {
                    _state.update { it.copy(sending = false) }
                    _event.value = ComposeEvent.Toast(mailText(context, MailHomeViewModel.code(r)))
                }
            }
        }
    }

    /**
     * Closing: what was written stays as a draft. Nothing written here leaves nothing behind: a draft the server made
     * for an answer goes again, a draft that was opened stays as it was.
     */
    fun close() {
        viewModelScope.launch {
            val s = _state.value
            if (!touched) {
                autosave?.cancel()
                if (argDraft == null) s.draftId?.let { api.deleteDraft(it) }
                _event.value = ComposeEvent.Discarded
                return@launch
            }
            if (dirty || s.draftId == null) save()
            _event.value = ComposeEvent.Saved
        }
    }

    /** "Verwerfen": the draft goes. */
    fun discard() {
        autosave?.cancel()
        viewModelScope.launch {
            _state.value.draftId?.let { api.deleteDraft(it) }
            _event.value = ComposeEvent.Discarded
        }
    }

    fun consumed() {
        _event.value = null
    }

    private companion object {
        const val AUTOSAVE_MS = 3_000L
    }
}

/** A Drive file picked in "Aus Drive": as a copy or as a link. */
data class MailDrivePick(val nodeId: Long, val name: String, val size: Long, val mime: String, val asLink: Boolean) {
    companion object {
        /** A link is valid this long (Drive's default). */
        const val LINK_DAYS = 14
    }
}
