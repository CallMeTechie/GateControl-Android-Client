package com.gatecontrol.android.ui.mail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MContact
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MailSenderState(
    val email: String = "",
    /** The name the mail gave. */
    val name: String? = null,
    val accountId: Long? = null,
    /**
     * The sender in the person's contacts (`contacts?q=`, a `person` hit), null without one. The place for what the
     * coming contacts plugin knows about them; for now only its name is shown.
     */
    val contact: MContact? = null,
    /** Since Mail 1.2.0 (`cards/lookup`): the sender's contact, null without one. */
    val card: com.gatecontrol.android.mail.MCard? = null,
    /** The lookup came (with contacts on): "Als Kontakt speichern" when [card] is null. */
    val looked: Boolean = false,
    /** A GateControl person with this address (their name). */
    val person: String? = null,
    /** Their mails in the account, newest first. */
    val items: List<MSummary> = emptyList(),
    val next: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** The name shown: from the contacts first, else from the mail. */
    val label: String get() = card?.label?.takeIf { it.isNotBlank() } ?: contact?.name?.takeIf { it.isNotBlank() } ?: person ?: name?.takeIf { it.isNotBlank() } ?: email
}

/** A sender's page: who it is (the contacts' name when there is one) and their mails in the account (`GET search?from=`). */
@HiltViewModel
class MailSenderViewModel @Inject constructor(
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me

    private val _state = MutableStateFlow(
        MailSenderState(
            email = saved.get<String>(Screen.MailSender.ARG_EMAIL)?.trim().orEmpty(),
            name = saved.get<String>(Screen.MailSender.ARG_NAME)?.trim()?.takeIf { it.isNotEmpty() },
            accountId = saved.get<String>(Screen.MailSender.ARG_ACCOUNT)?.toLongOrNull()?.takeIf { it > 0 },
        ),
    )
    val state: StateFlow<MailSenderState> = _state.asStateFlow()

    init {
        load()
    }

    /** The first page of their mails and their contact. */
    fun load() {
        val s = _state.value
        if (!MailLogic.isEmail(s.email)) {
            _state.update { it.copy(loading = false, error = "not_found") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            if (repository.me.value?.has(MMe.FEATURE_CONTACTS) == true) {
                // since Mail 1.2.0: the real contact (contract D5)
                api.lookup(s.email).valueOrNull()?.let { l -> _state.update { it.copy(card = l.card, person = l.personName, looked = true) } }
            } else {
                api.contacts(s.email).valueOrNull()?.let { list -> _state.update { it.copy(contact = MailLogic.contactOf(list, s.email)) } }
            }
        }
        viewModelScope.launch {
            when (val r = api.search(MailLogic.senderQuery(s.email, s.accountId))) {
                is MResult.Ok -> _state.update { it.copy(items = MailLogic.fromSender(r.value.items, s.email), next = r.value.next, loading = false, error = null) }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** The next page (the cursor of the last one). */
    fun more() {
        val s = _state.value
        val next = s.next ?: return
        if (s.loading) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val r = api.search(MailLogic.senderQuery(s.email, s.accountId, next))) {
                is MResult.Ok -> _state.update {
                    it.copy(items = MailLogic.merge(it.items, MailLogic.fromSender(r.value.items, s.email)), next = r.value.next, loading = false)
                }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }
}
