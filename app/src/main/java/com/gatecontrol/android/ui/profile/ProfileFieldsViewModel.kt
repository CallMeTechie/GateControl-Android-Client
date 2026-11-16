package com.gatecontrol.android.ui.profile

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatNewChat
import com.gatecontrol.android.profile.PBirthday
import com.gatecontrol.android.profile.ProfileDetails
import com.gatecontrol.android.profile.ProfileDetailsJson
import com.gatecontrol.android.profile.ProfileEdit
import com.gatecontrol.android.profile.ProfileExport
import com.gatecontrol.android.profile.ProfileRepository
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which field's dialog is open. */
enum class ProfileEditor { DISPLAY_NAME, NAMES, ABOUT, EMAIL, ADD_EMAIL, BIRTHDAY, LANGUAGE }

/**
 * The sheet of a phone number: [id] null for a new one. [input] is what is typed; the app checks it as the chat by
 * number does (a plus, the country code, the digits). Never logged: [toString] leaves the number out.
 */
data class PhoneDraft(
    val id: Long? = null,
    val kind: String = "mobile",
    val input: String = "",
    val visibility: String = ProfileDetails.VIS_NONE,
    val useForRecognition: Boolean = true,
    /** The number's state while editing one (`whatsapp`, …); null for a new one. */
    val state: String? = null,
) {
    /** E.164 of [input], null while it is not a number. */
    val e164: String? get() = ChatNewChat.number(input).e164

    override fun toString(): String = "PhoneDraft(id=$id, kind=$kind, visibility=$visibility, use=$useForRecognition)"
}

/**
 * The fields of the own profile (server 1.280+) on the page „Profil“: each change goes to the server, whose answer
 * (the whole profile) replaces ours. After the first number the question whether others may recognise the person by
 * it; „Meine Daten“ as a file through the system's save dialog.
 */
@HiltViewModel
class ProfileFieldsViewModel @Inject constructor(
    private val repository: ProfileRepository,
) : ViewModel() {

    val details: StateFlow<ProfileDetails?> = repository.state.map { it.details }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.state.value.details)

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _editor = MutableStateFlow<ProfileEditor?>(null)
    val editor: StateFlow<ProfileEditor?> = _editor.asStateFlow()

    private val _phone = MutableStateFlow<PhoneDraft?>(null)
    /** The open sheet of a number, null when closed. */
    val phone: StateFlow<PhoneDraft?> = _phone.asStateFlow()

    private val _refusal = MutableStateFlow<ProfileEdit.Refused?>(null)
    /** Why the last change in the open dialog or sheet was refused. */
    val refusal: StateFlow<ProfileEdit.Refused?> = _refusal.asStateFlow()

    private val _consent = MutableStateFlow(false)
    /** „Dürfen andere dich an dieser Nummer erkennen?“ after the first number. */
    val consent: StateFlow<Boolean> = _consent.asStateFlow()

    private val _emailOpen = MutableStateFlow<Long?>(null)
    /** The further address whose dialog is open (its state, code, new code, remove). */
    val emailOpen: StateFlow<Long?> = _emailOpen.asStateFlow()

    private val _emailCode = MutableStateFlow<Long?>(null)
    /** The further address whose code is asked for. */
    val emailCode: StateFlow<Long?> = _emailCode.asStateFlow()

    private val _export = MutableStateFlow<ProfileExport.File?>(null)
    /** „Meine Daten“ fetched, waiting for the save dialog. */
    val export: StateFlow<ProfileExport.File?> = _export.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    // ─── dialogs of the fields ─────────────────────────────────────

    fun open(editor: ProfileEditor) {
        val d = details.value ?: return
        if (editor == ProfileEditor.DISPLAY_NAME && d.locked.displayName) return
        if (editor == ProfileEditor.EMAIL && d.locked.email) return
        _refusal.value = null
        _editor.value = editor
    }

    fun close() {
        if (_saving.value) return
        _editor.value = null
        _refusal.value = null
    }

    fun saveDisplayName(name: String) {
        if (name.isBlank()) return refuse("INVALID", "displayName")
        save(ProfileDetailsJson.textBody("displayName" to name))
    }

    /** First and last name share one visibility. */
    fun saveNames(first: String, last: String, visibility: String) = save(
        ProfileDetailsJson.merge(
            ProfileDetailsJson.textBody("firstName" to first, "lastName" to last),
            ProfileDetailsJson.visibilityBody(visibility, "firstName", "lastName"),
        ),
    )

    fun saveAbout(about: String, visibility: String) = save(
        ProfileDetailsJson.merge(ProfileDetailsJson.textBody("about" to about), ProfileDetailsJson.visibilityBody(visibility, "about")),
    )

    fun saveEmail(address: String, visibility: String) {
        val d = details.value ?: return
        val parts = mutableListOf(ProfileDetailsJson.visibilityBody(visibility, "email"))
        if (!d.locked.email) parts += ProfileDetailsJson.textBody("email" to address)
        save(ProfileDetailsJson.merge(*parts.toTypedArray()))
    }

    /** [birthday] null: removed. */
    fun saveBirthday(birthday: PBirthday?, visibility: String) = save(
        ProfileDetailsJson.merge(ProfileDetailsJson.birthdayBody(birthday), ProfileDetailsJson.visibilityBody(visibility, "birthday")),
    )

    /** [timezone] null: as the server. Language and time zone share one visibility. */
    fun saveLanguage(language: String?, timezone: String?, visibility: String) {
        val body = ProfileDetailsJson.merge(
            JsonObject().apply {
                language?.let { addProperty("language", it) }
                if (timezone == null) add("timezone", com.google.gson.JsonNull.INSTANCE) else addProperty("timezone", timezone)
            },
            ProfileDetailsJson.visibilityBody(visibility, "language", "timezone"),
        )
        save(body)
    }

    private fun save(body: JsonObject) = run(close = true) { repository.updateFields(body) }

    /** Runs a change; Done closes the dialog ([close]), a refusal stays in it. */
    private fun run(close: Boolean, onDone: (ProfileEdit.Done) -> Unit = {}, call: suspend () -> ProfileEdit) {
        if (_saving.value) return
        _saving.value = true
        _refusal.value = null
        viewModelScope.launch {
            val r = call()
            _saving.value = false
            when (r) {
                is ProfileEdit.Done -> {
                    if (close) _editor.value = null
                    onDone(r)
                }
                is ProfileEdit.Refused -> _refusal.value = r
            }
        }
    }

    private fun refuse(code: String, field: String?) {
        _refusal.value = ProfileEdit.Refused(code, field)
    }

    // ─── recognition ───────────────────────────────────────────────

    fun setRecognition(byWhatsapp: Boolean? = null, byPhone: Boolean? = null, bySignal: Boolean? = null) = run(close = false, onDone = {}) {
        repository.updateFields(ProfileDetailsJson.recognitionBody(byWhatsapp, byPhone, bySignal)).also { r ->
            if (r is ProfileEdit.Refused) _messages.tryEmit(messageOf(r.code))
        }
    }

    /** The answer to the question after the first number (also „Nein“ is kept, with its time, by the server). */
    fun answerConsent(yes: Boolean) {
        _consent.value = false
        setRecognition(byPhone = yes)
    }

    // ─── phone numbers ─────────────────────────────────────────────

    fun addPhone() {
        val d = details.value ?: return
        if (!d.canAddPhone) return
        _refusal.value = null
        _phone.value = PhoneDraft()
    }

    fun editPhone(id: Long) {
        val p = details.value?.phones?.firstOrNull { it.id == id } ?: return
        _refusal.value = null
        _phone.value = PhoneDraft(p.id, p.kind, ChatNewChat.number(p.number).display ?: p.number, p.visibility, p.useForRecognition, p.state)
    }

    fun updateDraft(change: (PhoneDraft) -> PhoneDraft) {
        val d = _phone.value ?: return
        _phone.value = change(d)
        if (_refusal.value?.field == "number") _refusal.value = null
    }

    fun closePhone() {
        if (_saving.value) return
        _phone.value = null
        _refusal.value = null
    }

    fun savePhone() {
        val draft = _phone.value ?: return
        val d = details.value ?: return
        val e164 = draft.e164 ?: return refuse("INVALID", "number")
        val id = draft.id
        if (id == null) {
            val first = d.phones.isEmpty()
            run(close = false, onDone = { done ->
                _phone.value = null
                // the question once: after the first number, while the person was never asked
                if (first && done.details.features.recognition && !done.details.recognition.phoneAnswered) _consent.value = true
            }) { repository.addPhone(draft.kind, e164, draft.visibility, draft.useForRecognition) }
            return
        }
        val old = d.phones.firstOrNull { it.id == id } ?: return closePhone()
        val kind = draft.kind.takeIf { it != old.kind }
        val number = e164.takeIf { it != old.number }
        val vis = draft.visibility.takeIf { it != old.visibility }
        val use = draft.useForRecognition.takeIf { it != old.useForRecognition }
        if (kind == null && number == null && vis == null && use == null) return closePhone()
        run(close = false, onDone = { _phone.value = null }) { repository.updatePhone(id, kind, number, vis, use) }
    }

    fun removePhone() {
        val id = _phone.value?.id ?: return
        run(close = false, onDone = { _phone.value = null; _messages.tryEmit(R.string.profile_phone_removed) }) { repository.removePhone(id) }
    }

    // ─── further e-mail addresses ─────────────────────────────────

    fun addEmail(address: String) {
        val a = address.trim()
        if (!EMAIL_RE.matches(a)) return refuse("INVALID", "address")
        run(close = true, onDone = { done ->
            if (done.codeSent && done.emailId != null) _emailCode.value = done.emailId
            else if (!done.details.features.emailConfirm) _messages.tryEmit(R.string.profile_email_no_mail)
        }) { repository.addEmail(a) }
    }

    fun openEmail(id: Long) {
        _refusal.value = null
        _emailOpen.value = id
    }

    fun askCode(id: Long) {
        _refusal.value = null
        _emailCode.value = id
    }

    fun closeEmail() {
        if (_saving.value) return
        _emailCode.value = null
        _emailOpen.value = null
        _refusal.value = null
    }

    fun confirmEmail(code: String) {
        val id = _emailCode.value ?: return
        val c = code.trim()
        if (!CODE_RE.matches(c)) return refuse("WRONG_CODE", "code")
        run(close = false, onDone = { _emailCode.value = null; _emailOpen.value = null; _messages.tryEmit(R.string.profile_email_confirmed) }) { repository.confirmEmail(id, c) }
    }

    fun resendEmail(id: Long) = run(close = false, onDone = { done ->
        _messages.tryEmit(if (done.codeSent) R.string.profile_email_code_sent else R.string.profile_email_no_mail)
    }) { repository.resendEmail(id).also { r -> if (r is ProfileEdit.Refused && _emailCode.value == null) _messages.tryEmit(messageOf(r.code)) } }

    fun removeEmail(id: Long) = run(close = false, onDone = { _emailCode.value = null; _emailOpen.value = null }) {
        repository.removeEmail(id).also { r -> if (r is ProfileEdit.Refused) _messages.tryEmit(messageOf(r.code)) }
    }

    // ─── „Meine Daten“ ────────────────────────────────────────────

    fun startExport() {
        if (_exporting.value || _export.value != null) return
        _exporting.value = true
        viewModelScope.launch {
            when (val r = repository.export()) {
                is ProfileExport.File -> _export.value = r
                is ProfileExport.Refused -> _messages.tryEmit(messageOf(r.code))
            }
            _exporting.value = false
        }
    }

    /** The save dialog is done (saved or cancelled): the file leaves the memory. [saved] null: cancelled. */
    fun exportDone(saved: Boolean?) {
        _export.value = null
        when (saved) {
            true -> _messages.tryEmit(R.string.profile_export_saved)
            false -> _messages.tryEmit(R.string.profile_error_failed)
            null -> Unit
        }
    }

    companion object {
        private val EMAIL_RE = Regex("^[^\\s@]{1,64}@[^\\s@]{1,253}\\.[^\\s@]{2,63}$")
        private val CODE_RE = Regex("^[0-9]{6}$")

        /** Pure: the server's code → the app's text when the server sent none. */
        @StringRes
        fun messageOf(code: String): Int = when (code) {
            ProfileRepository.CODE_OFFLINE -> R.string.profile_error_offline
            "RATE_LIMITED" -> R.string.profile_error_rate_limited
            "LIMIT" -> R.string.profile_error_limit
            "DUPLICATE" -> R.string.profile_error_duplicate
            "LOCKED" -> R.string.profile_locked
            "PHONES_OFF" -> R.string.profile_error_phones_off
            "NO_MAIL" -> R.string.profile_email_no_mail
            "WRONG_CODE" -> R.string.profile_error_wrong_code
            "CODE_EXPIRED" -> R.string.profile_error_code_expired
            "NOT_FOUND" -> R.string.profile_error_not_found
            "NO_USER" -> R.string.profile_no_user
            "INVALID" -> R.string.profile_error_field
            ProfileRepository.CODE_TOO_LARGE -> R.string.profile_error_failed
            else -> R.string.profile_error_failed
        }
    }
}
