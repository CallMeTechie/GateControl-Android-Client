package com.gatecontrol.android.ui.mail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MFilingItem
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MOutboxItem
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSettings
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailDisplaySettings
import com.gatecontrol.android.mail.MailJson
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Zum Ablegen" (mockup "Zum Ablegen"): attachments the AI was not sure about. */
@HiltViewModel
class MailFilingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MailRepository,
) : ViewModel() {

    data class State(val items: List<MFilingItem> = emptyList(), val loading: Boolean = true, val error: String? = null, val busy: Set<Long> = emptySet())

    val me: StateFlow<MMe?> = repository.me
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            when (val r = repository.api.filing()) {
                is MResult.Ok -> _state.value = State(items = r.value, loading = false)
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** "Ablegen" (the suggestion) or into the folder the person picked. */
    fun confirm(item: MFilingItem, folderId: Long? = null) {
        _state.update { it.copy(busy = it.busy + item.id) }
        viewModelScope.launch {
            val r = repository.api.confirmFiling(item.id, folderId ?: item.suggestion?.folderId, if (folderId == null) item.suggestion?.name else null)
            _state.update { s -> s.copy(busy = s.busy - item.id, items = if (r is MResult.Ok) s.items.filter { it.id != item.id } else s.items) }
            _toast.value = when (r) {
                is MResult.Ok -> context.getString(R.string.mail_saved_in, r.value.where)
                else -> mailText(context, MailHomeViewModel.code(r))
            }
        }
    }

    /** "Nicht ablegen". */
    fun dismiss(item: MFilingItem) {
        _state.update { it.copy(busy = it.busy + item.id) }
        viewModelScope.launch {
            val r = repository.api.dismissFiling(item.id)
            _state.update { s -> s.copy(busy = s.busy - item.id, items = if (r is MResult.Ok) s.items.filter { it.id != item.id } else s.items) }
            if (r !is MResult.Ok) _toast.value = mailText(context, MailHomeViewModel.code(r))
        }
    }

    /** "Alle ablegen": every item with a suggestion. */
    fun confirmAll() {
        _state.value.items.filter { it.suggestion != null && it.id !in _state.value.busy }.forEach { confirm(it) }
    }

    fun toastShown() {
        _toast.value = null
    }
}

/** "Postausgang": sends planned or failed; one can be stopped (its draft opens again). */
@HiltViewModel
class MailOutboxViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MailRepository,
) : ViewModel() {

    data class State(val items: List<MOutboxItem> = emptyList(), val loading: Boolean = true, val error: String? = null)

    val me: StateFlow<MMe?> = repository.me
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _open = MutableStateFlow<Long?>(null)
    /** A stopped send's draft, to write on. */
    val open: StateFlow<Long?> = _open.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            when (val r = repository.api.outbox()) {
                is MResult.Ok -> _state.value = State(items = r.value, loading = false)
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun cancel(item: MOutboxItem) {
        viewModelScope.launch {
            when (val r = repository.api.cancel(item.id)) {
                is MResult.Ok -> {
                    _state.update { s -> s.copy(items = s.items.filter { it.id != item.id }) }
                    repository.clearUndo(item.id)
                    (r.value ?: item.draftId)?.let { _open.value = it }
                }
                else -> _toast.value = mailText(context, MailHomeViewModel.code(r))
            }
        }
    }

    fun opened() {
        _open.value = null
    }

    fun toastShown() {
        _toast.value = null
    }
}

/** The search (`GET search`): words, unread or with attachments, in all accounts or one. */
@HiltViewModel
class MailSearchViewModel @Inject constructor(private val repository: MailRepository) : ViewModel() {

    data class State(
        val query: String = "",
        val unread: Boolean = false,
        val attachments: Boolean = false,
        val accountId: Long? = null,
        val items: List<MSummary> = emptyList(),
        val next: String? = null,
        val remote: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
    )

    val me: StateFlow<MMe?> = repository.me
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        search()
    }

    fun toggleUnread() {
        _state.update { it.copy(unread = !it.unread) }
        search()
    }

    fun toggleAttachments() {
        _state.update { it.copy(attachments = !it.attachments) }
        search()
    }

    fun setAccount(id: Long?) {
        _state.update { it.copy(accountId = id) }
        search()
    }

    private fun query(s: State, cursor: String?): Map<String, String> = buildMap {
        put("q", s.query.trim())
        put("box", s.accountId?.toString() ?: "all")
        if (s.unread) put("unread", "1")
        if (s.attachments) put("attachments", "1")
        cursor?.let { put("cursor", it) }
    }

    private fun search() {
        job?.cancel()
        val s = _state.value
        if (s.query.trim().length < 2 && !s.unread && !s.attachments) {
            _state.update { it.copy(items = emptyList(), next = null, busy = false, error = null) }
            return
        }
        job = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(busy = true) }
            when (val r = repository.api.search(query(_state.value, null))) {
                is MResult.Ok -> _state.update { it.copy(items = r.value.items, next = r.value.next, remote = r.value.remote, busy = false, error = null) }
                else -> _state.update { it.copy(busy = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun more() {
        val s = _state.value
        val next = s.next ?: return
        if (s.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = repository.api.search(query(s, next))) {
                is MResult.Ok -> _state.update { it.copy(items = com.gatecontrol.android.mail.MailLogic.merge(it.items, r.value.items), next = r.value.next, busy = false) }
                else -> _state.update { it.copy(busy = false) }
            }
        }
    }
}

/** "Mails dunkel darstellen" (kept on this phone): the reading screen and "Mail-Einstellungen › Darstellung". */
@HiltViewModel
class MailDisplayViewModel @Inject constructor(private val settings: MailDisplaySettings) : ViewModel() {
    val darkMails: StateFlow<Boolean> = settings.darkMails

    fun setDarkMails(on: Boolean) = settings.setDarkMails(on)
}

/** "Mail-Einstellungen": the list settings (`PUT settings`) and the accounts. */
@HiltViewModel
class MailSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MailRepository,
) : ViewModel() {

    val me: StateFlow<MMe?> = repository.me
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        repository.refreshSoon()
    }

    fun put(threads: Boolean? = null, swipeLeft: String? = null, swipeRight: String? = null) {
        val me = repository.me.value ?: return
        // at once on the screen
        repository.setMe(me.copy(settings = me.settings.copy(threads = threads ?: me.settings.threads, swipeLeft = swipeLeft ?: me.settings.swipeLeft, swipeRight = swipeRight ?: me.settings.swipeRight)))
        viewModelScope.launch {
            when (val r = repository.api.putSettings(threads, swipeLeft, swipeRight)) {
                is MResult.Ok -> repository.me.value?.let { repository.setMe(it.copy(settings = r.value)) }
                else -> {
                    repository.setMe(me)
                    _toast.value = mailText(context, MailHomeViewModel.code(r))
                }
            }
        }
    }

    fun toastShown() {
        _toast.value = null
    }
}

/** One account's settings: name, color, kind, push, AI, attachments to Drive, signature, password; remove it. */
@HiltViewModel
class MailAccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    val id: Long = saved.get<String>(Screen.MailAccount.ARG_ID)?.toLongOrNull() ?: 0L
    val me: StateFlow<MMe?> = repository.me
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    private val _gone = MutableStateFlow(false)
    /** The account was removed: the screen closes. */
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    fun account(): MAccount? = repository.me.value?.account(id)

    /** A change (only the given fields, `PATCH accounts/<id>`). */
    fun patch(
        name: String? = null,
        color: String? = null,
        kind: String? = null,
        push: String? = null,
        ai: Boolean? = null,
        attachments: String? = null,
        signature: String? = null,
        password: String? = null,
    ) {
        _busy.value = true
        viewModelScope.launch {
            val r = repository.api.patchAccount(id, MailJson.accountPatch(name, color, kind, push, ai, attachments, signature, password))
            when (r) {
                is MResult.Ok -> {
                    repository.me.value?.let { me -> repository.setMe(me.copy(accounts = me.accounts.map { if (it.id == id) r.value else it })) }
                    if (password != null || signature != null || name != null) _toast.value = context.getString(R.string.mail_saved)
                }
                else -> _toast.value = mailText(context, MailHomeViewModel.code(r)) +
                    (MailJson.errorHint((r as? MResult.Failed)?.error, context.resources.configuration.locales[0].language)?.let { "\n$it" } ?: "")
            }
            _busy.value = false
        }
    }

    fun remove() {
        _busy.value = true
        viewModelScope.launch {
            when (val r = repository.api.deleteAccount(id)) {
                is MResult.Ok -> {
                    repository.cache.forgetAccount(id)
                    repository.refresh()
                    _gone.value = true
                }
                else -> _toast.value = mailText(context, MailHomeViewModel.code(r))
            }
            _busy.value = false
        }
    }

    fun toastShown() {
        _toast.value = null
    }

    companion object {
        val SWIPES = MSettings.SWIPES
    }
}
