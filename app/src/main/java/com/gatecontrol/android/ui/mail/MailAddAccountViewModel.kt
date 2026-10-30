package com.gatecontrol.android.ui.mail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MProvider
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MServer
import com.gatecontrol.android.mail.MailJson
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.mail.MailMicrosoft
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.mail.MailSigninRedirects
import com.gatecontrol.android.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** The form of "<Anbieter> einrichten". */
data class AccountForm(
    val email: String = "",
    val password: String = "",
    val name: String = "",
    val color: String = MailLogic.COLORS.first(),
    val kind: String = MAccount.KIND_PERSONAL,
    val username: String = "",
    val imapHost: String = "",
    val imapPort: String = "993",
    val imapSecurity: String = "tls",
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpSecurity: String = "tls",
    /** "Andere / eigene Domain": the servers by hand (filled in by the discovery when it finds them). */
    val manual: Boolean = false,
)

data class AddAccountState(
    val providers: List<MProvider> = emptyList(),
    val loading: Boolean = true,
    val provider: MProvider? = null,
    val form: AccountForm = AccountForm(),
    val hint: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val errorHint: String? = null,
    /** Waiting for the browser's answer of the Microsoft sign-in. */
    val waitingBrowser: Boolean = false,
)

sealed class AddAccountEvent {
    /** Open the Microsoft sign-in page in a Custom Tab. */
    data class Browser(val url: String) : AddAccountEvent()
    data class Done(val account: MAccount) : AddAccountEvent()
}

/**
 * "Konto hinzufügen" (mockup "Konto hinzufügen"): the providers, the form with the provider's hint and "Verbinden"
 * (the server checks IMAP and SMTP first), or "Mit Microsoft anmelden" (PKCE in the browser, the server redeems the
 * code). With `account` given: sign in to that Microsoft account again.
 */
@HiltViewModel
class MailAddAccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MailRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    /** Sign in to this account again (Microsoft). */
    val relogin: Long? = saved.get<String>(Screen.MailAddAccount.ARG_ACCOUNT)?.toLongOrNull()

    private val _state = MutableStateFlow(AddAccountState())
    val state: StateFlow<AddAccountState> = _state.asStateFlow()

    private val _event = MutableStateFlow<AddAccountEvent?>(null)
    val event: StateFlow<AddAccountEvent?> = _event.asStateFlow()

    init {
        viewModelScope.launch {
            val me = repository.me.value ?: repository.refresh().valueOrNull()
            val used = me?.accounts?.map { it.color }.orEmpty()
            _state.update { it.copy(form = it.form.copy(color = MailLogic.nextColor(used))) }
            val list = api.providers()
            _state.update {
                it.copy(
                    loading = false,
                    providers = withMicrosoft(list.valueOrNull().orEmpty()),
                    error = if (list is MResult.Ok) null else MailHomeViewModel.code(list),
                )
            }
            // "Neu anmelden" of a Microsoft account: straight to the browser (not again after a restart while it is open)
            if (relogin != null && saved.get<String>(KEY_STATE) == null && me?.account(relogin)?.microsoft == true) {
                _state.update { s -> s.copy(provider = s.providers.firstOrNull { it.auth == MProvider.AUTH_MICROSOFT }) }
                startMicrosoft()
            }
        }
        // the browser's answer (MailSigninActivity): only one this screen asked for
        viewModelScope.launch {
            MailSigninRedirects.pending.filterNotNull().collect { raw ->
                MailSigninRedirects.consume(raw)
                onRedirect(raw)
            }
        }
    }

    /** The server's list, with a Microsoft entry when it has none (contract: always listed, with a hint when off). */
    private fun withMicrosoft(list: List<MProvider>): List<MProvider> {
        if (list.any { it.auth == MProvider.AUTH_MICROSOFT }) return list
        return listOf(MProvider(MAccount.PROVIDER_M365, context.getString(R.string.mail_provider_microsoft), MProvider.AUTH_MICROSOFT, null, null, null)) + list
    }

    fun microsoftReady(): Boolean = me.value?.let { it.has(MMe.FEATURE_MICROSOFT) && it.microsoft.clientId != null } == true

    fun lang(): String = context.resources.configuration.locales[0].language.takeIf { it == "en" } ?: "de"

    fun pick(p: MProvider?) {
        _state.update { s ->
            s.copy(
                provider = p,
                hint = p?.hint(lang()),
                error = null,
                errorHint = null,
                form = s.form.copy(
                    manual = p?.auth == MProvider.AUTH_IMAP,
                    name = s.form.name.ifEmpty { p?.label.orEmpty() },
                ),
            )
        }
        if (p?.auth == MProvider.AUTH_MICROSOFT) startMicrosoft()
    }

    fun edit(change: (AccountForm) -> AccountForm) = _state.update { it.copy(form = change(it.form), error = null, errorHint = null) }

    /** "Andere / eigene Domain": the servers of the address, when the server finds them. */
    fun discover() {
        val email = _state.value.form.email.trim()
        if (!MailLogic.isEmail(email) || _state.value.provider?.auth != MProvider.AUTH_IMAP) return
        viewModelScope.launch {
            val d = api.discover(email).valueOrNull() ?: return@launch
            _state.update { s ->
                s.copy(
                    hint = (if (lang() == "en") d.hintEn ?: d.hintDe else d.hintDe ?: d.hintEn) ?: s.hint,
                    form = s.form.copy(
                        imapHost = s.form.imapHost.ifEmpty { d.imap?.host.orEmpty() },
                        imapPort = d.imap?.port?.toString() ?: s.form.imapPort,
                        imapSecurity = d.imap?.security ?: s.form.imapSecurity,
                        smtpHost = s.form.smtpHost.ifEmpty { d.smtp?.host.orEmpty() },
                        smtpPort = d.smtp?.port?.toString() ?: s.form.smtpPort,
                        smtpSecurity = d.smtp?.security ?: s.form.smtpSecurity,
                    ),
                )
            }
        }
    }

    /** "Verbinden". */
    fun connect() {
        val s = _state.value
        val p = s.provider ?: return
        val f = s.form
        if (!MailLogic.isEmail(f.email) || f.password.isEmpty()) {
            _state.update { it.copy(error = "invalid") }
            return
        }
        val imap = if (f.manual && f.imapHost.isNotBlank()) MServer(f.imapHost.trim(), f.imapPort.toIntOrNull() ?: 993, f.imapSecurity) else null
        val smtp = if (f.manual && f.smtpHost.isNotBlank()) MServer(f.smtpHost.trim(), f.smtpPort.toIntOrNull() ?: 465, f.smtpSecurity) else null
        _state.update { it.copy(busy = true, error = null, errorHint = null) }
        viewModelScope.launch {
            val r = api.addAccount(MailJson.accountBody(p.id, f.email, f.password, f.username.takeIf { f.manual }, f.name, f.color, f.kind, imap, smtp))
            done(r)
        }
    }

    private suspend fun done(r: MResult<MAccount>) {
        when (r) {
            is MResult.Ok -> {
                repository.refresh()
                _state.update { it.copy(busy = false, waitingBrowser = false) }
                _event.value = AddAccountEvent.Done(r.value)
            }
            else -> _state.update {
                it.copy(
                    busy = false,
                    waitingBrowser = false,
                    error = MailHomeViewModel.code(r),
                    errorHint = MailJson.errorHint((r as? MResult.Failed)?.error, lang()),
                )
            }
        }
    }

    // ─── Microsoft ──────────────────────────────────────────────────

    /** "Mit Microsoft anmelden": the sign-in page in the browser; verifier and state stay here (and survive a restart). */
    fun startMicrosoft() {
        val me = me.value
        val clientId = me?.microsoft?.clientId
        if (me == null || !me.has(MMe.FEATURE_MICROSOFT) || clientId == null) {
            _state.update { it.copy(error = MS_NOT_SET_UP) }
            return
        }
        val hash = MailMicrosoft.signatureHash(context)
        if (hash == null) {
            _state.update { it.copy(error = "failed") }
            return
        }
        val redirect = MailMicrosoft.redirectUri(hash)
        // in the support log: the redirect to compare with the app registration ("redirect_uri is not valid")
        Timber.i("Mail: Microsoft sign-in, redirect %s", redirect)
        val verifier = MailMicrosoft.newVerifier()
        val st = MailMicrosoft.newState()
        val hint = relogin?.let { me.account(it)?.email }
        val url = MailMicrosoft.authorizeUrl(me.microsoft.authority, clientId, me.microsoft.scopes, redirect, st, MailMicrosoft.challenge(verifier), hint)
        if (url == null) {
            _state.update { it.copy(error = "failed") }
            return
        }
        saved[KEY_VERIFIER] = verifier
        saved[KEY_STATE] = st
        saved[KEY_REDIRECT] = redirect
        _state.update { it.copy(waitingBrowser = true, error = null) }
        _event.value = AddAccountEvent.Browser(url)
    }

    private fun onRedirect(raw: String) {
        val answer = MailMicrosoft.parse(raw) ?: return
        val verifier = saved.get<String>(KEY_VERIFIER)
        val expected = saved.get<String>(KEY_STATE)
        val redirect = saved.get<String>(KEY_REDIRECT)
        // not ours (another sign-in, or a replay): ignored
        if (verifier == null || expected == null || redirect == null || answer.state != expected) return
        saved.remove<String>(KEY_VERIFIER)
        saved.remove<String>(KEY_STATE)
        val code = answer.code
        if (code == null) {
            _state.update { it.copy(waitingBrowser = false, error = if (answer.error == "access_denied") "cancelled" else "ms_failed", errorHint = answer.description) }
            return
        }
        _state.update { it.copy(busy = true, waitingBrowser = true) }
        viewModelScope.launch {
            val f = _state.value.form
            val r = relogin?.let { api.reloginMicrosoft(it, MailJson.microsoftBody(code, verifier, redirect, null, null, null)) }
                ?: api.addMicrosoft(MailJson.microsoftBody(code, verifier, redirect, f.name.takeIf { it.isNotBlank() && it != _state.value.provider?.label }, f.color, null))
            done(r)
        }
    }

    /** The browser came back without an answer (closed): the form may be used again. */
    fun browserClosed() {
        if (_state.value.waitingBrowser && !_state.value.busy) _state.update { it.copy(waitingBrowser = false) }
    }

    fun consumed() {
        _event.value = null
    }

    companion object {
        const val MS_NOT_SET_UP = "ms_not_set_up"
        private const val KEY_VERIFIER = "ms_verifier"
        private const val KEY_STATE = "ms_state"
        private const val KEY_REDIRECT = "ms_redirect"
    }
}
