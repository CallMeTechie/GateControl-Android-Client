package com.gatecontrol.android.ui.mail.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MBooks
import com.gatecontrol.android.mail.MCard
import com.gatecontrol.android.mail.MCardAddress
import com.gatecontrol.android.mail.MCardEmail
import com.gatecontrol.android.mail.MCardName
import com.gatecontrol.android.mail.MCardPhone
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.navigation.Screen
import com.gatecontrol.android.ui.mail.MailHomeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A new photo (JPEG, at most 512 px) or the photo's removal. */
sealed class PhotoChange {
    class Set(val bytes: ByteArray) : PhotoChange()
    data object Remove : PhotoChange()
}

data class ContactEditState(
    val card: MCard = MCard(emails = listOf(MCardEmail("", "work", true)), phones = listOf(MCardPhone("", "mobile"))),
    /** New contacts: the address book it goes into (writable ones). */
    val addressbookId: Long? = null,
    /** The birthday as typed: day and month (`14.03.`) and the year (optional). */
    val birthdayDayMonth: String = "",
    val birthdayYear: String = "",
    val photo: PhotoChange? = null,
    /** "Als Kontakt speichern": the contact that has the address already ("Zu <Name> hinzufügen"). */
    val existing: MCard? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val conflict: MCard? = null,
    val message: String? = null,
    /** Saved: the contact's id (the screen closes). */
    val done: Long? = null,
)

/**
 * A new contact, a change, or "Als Kontakt speichern" from a mail (contract D4/D5): the form, photo, groups,
 * the address book of a new one, saving with the etag and its conflicts.
 */
@HiltViewModel
class MailContactEditViewModel @Inject constructor(
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val pim = repository.pim
    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val books: StateFlow<MBooks?> = pim.books

    private var id: Long? = saved.get<String>(Screen.MailContactEdit.ARG_ID)?.toLongOrNull()?.takeIf { it > 0 }
    private val message: Long? = saved.get<String>(Screen.MailContactEdit.ARG_MESSAGE)?.toLongOrNull()?.takeIf { it > 0 }
    private val email: String? = saved.get<String>(Screen.MailContactEdit.ARG_EMAIL)?.trim()?.takeIf { it.contains('@') }
    private val name: String? = saved.get<String>(Screen.MailContactEdit.ARG_NAME)?.trim()?.takeIf { it.isNotEmpty() }
    private val group: Long? = saved.get<String>(Screen.MailContactEdit.ARG_GROUP)?.toLongOrNull()?.takeIf { it > 0 }

    private val _state = MutableStateFlow(ContactEditState())
    val state: StateFlow<ContactEditState> = _state.asStateFlow()

    val isNew: Boolean get() = id == null

    init {
        if (pim.books.value == null) viewModelScope.launch { pim.refreshBooks() }
        when {
            id != null -> load(id!!)
            email != null -> draft(email)
            else -> start(MCard(name = splitName(name), groups = listOfNotNull(group)))
        }
    }

    private fun splitName(n: String?): MCardName {
        n ?: return MCardName()
        val parts = n.split(Regex("\\s+"))
        return if (parts.size >= 2) MCardName(given = parts.dropLast(1).joinToString(" "), family = parts.last()) else MCardName(given = n)
    }

    private fun start(card: MCard) {
        val b = MailPimLogic.birthdayParts(card.birthday)
        _state.update {
            it.copy(
                card = card.copy(
                    emails = card.emails.ifEmpty { listOf(MCardEmail("", "work", true)) },
                    phones = card.phones.ifEmpty { listOf(MCardPhone("", "mobile")) },
                ),
                birthdayDayMonth = b?.let { (m, d, _) -> "%02d.%02d.".format(d, m) }.orEmpty(),
                birthdayYear = b?.third?.toString().orEmpty(),
                addressbookId = it.addressbookId ?: defaultBook(),
                loading = false,
            )
        }
    }

    private fun defaultBook(): Long? {
        val b = pim.books.value ?: return null
        val writable = b.items.filter { it.writable }
        return b.prefs.defaultAddressbookId?.takeIf { d -> writable.any { it.id == d } } ?: writable.firstOrNull { it.isDefault }?.id ?: writable.firstOrNull()?.id
    }

    private fun load(cardId: Long) {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val r = pim.card(cardId)) {
                is MResult.Ok -> start(r.value)
                else -> _state.update { it.copy(loading = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** "Als Kontakt speichern": name and address from the mail, phone, company and position from the signature. */
    private fun draft(address: String) {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val r = api.cardDraft(message, address)) {
                is MResult.Ok -> {
                    val d = r.value.draft
                    start(d.copy(name = if (d.name.given.isBlank() && d.name.family.isBlank()) splitName(name ?: d.name.display.ifBlank { null }) else d.name, groups = listOfNotNull(group)))
                    _state.update { it.copy(existing = r.value.existing) }
                }
                else -> start(MCard(name = splitName(name), emails = listOf(MCardEmail(address, "other", true)), groups = listOfNotNull(group)))
            }
        }
    }

    /** "Zu <Name> hinzufügen": the existing contact with the new addresses and numbers. */
    fun addToExisting() {
        val ex = _state.value.existing ?: return
        val draft = _state.value.card
        id = ex.id
        start(
            ex.copy(
                emails = (ex.emails + draft.emails.filter { it.value.isNotBlank() }).distinctBy { it.value.lowercase() },
                phones = (ex.phones + draft.phones.filter { it.value.isNotBlank() }).distinctBy { MailPimLogic.phoneKey(it.value).ifEmpty { it.value } },
                company = ex.company ?: draft.company,
                title = ex.title ?: draft.title,
            ),
        )
        _state.update { it.copy(existing = null) }
    }

    fun edit(change: (MCard) -> MCard) = _state.update { it.copy(card = change(it.card)) }

    fun setBook(bookId: Long) = _state.update { it.copy(addressbookId = bookId) }

    fun setBirthday(dayMonth: String, year: String) = _state.update { it.copy(birthdayDayMonth = dayMonth.take(6), birthdayYear = year.filter { c -> c.isDigit() }.take(4)) }

    fun setPhoto(change: PhotoChange?) = _state.update { it.copy(photo = change) }

    /** Whether the birthday as typed is no date. */
    fun birthdayBad(s: ContactEditState = _state.value): Boolean =
        (s.birthdayDayMonth.isNotBlank() || s.birthdayYear.isNotBlank()) && MailPimLogic.birthdayOf(s.birthdayDayMonth, s.birthdayYear) == null

    fun canSave(s: ContactEditState = _state.value): Boolean {
        val c = s.card
        val any = c.name.given.isNotBlank() || c.name.family.isNotBlank() || !c.company.isNullOrBlank() || c.emails.any { it.value.isNotBlank() } || c.phones.any { it.value.isNotBlank() }
        return any && !birthdayBad(s) && !s.saving && !s.loading && (s.card.emails.all { it.value.isBlank() || it.value.contains('@') })
    }

    private fun form(s: ContactEditState): MCard {
        val c = s.card
        val emails = c.emails.filter { it.value.isNotBlank() }.mapIndexed { i, e -> e.copy(value = e.value.trim(), primary = i == 0) }
        return c.copy(
            emails = emails,
            phones = c.phones.filter { it.value.isNotBlank() },
            addresses = c.addresses.filter { !it.empty },
            birthday = MailPimLogic.birthdayOf(s.birthdayDayMonth, s.birthdayYear),
        )
    }

    fun save() = send(_state.value.card.etag)

    private fun send(etag: String?) {
        val s = _state.value
        if (!canSave(s)) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val cardId = id
            val r = if (cardId == null) api.createCard(MailPimJson.cardBody(form(s), addressbookId = s.addressbookId))
            else api.patchCard(cardId, MailPimJson.cardBody(form(s), etag = etag))
            when (r) {
                is MResult.Ok -> {
                    when (val p = s.photo) {
                        is PhotoChange.Set -> api.putCardPhoto(r.value.id, "image/jpeg", p.bytes)
                        PhotoChange.Remove -> if (r.value.photo) api.deleteCardPhoto(r.value.id)
                        null -> Unit
                    }
                    pim.syncCardsSoon()
                    _state.update { it.copy(saving = false, done = r.value.id) }
                }
                is MResult.Failed -> {
                    val current = if (r.code == "conflict") MailPimJson.conflictCard(r.error) else null
                    _state.update { if (current != null) it.copy(saving = false, conflict = current) else it.copy(saving = false, message = r.code) }
                }
                else -> _state.update { it.copy(saving = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** Conflict: "Meine Änderung erneut anwenden" (the form over the newer state). */
    fun applyAgain() {
        val current = _state.value.conflict ?: return
        _state.update { it.copy(conflict = null, card = it.card.copy(etag = current.etag)) }
        send(current.etag)
    }

    /** Conflict: "Neu laden" (the newer state into the form). */
    fun reloadConflict() {
        val current = _state.value.conflict ?: return
        _state.update { it.copy(conflict = null) }
        start(current)
    }

    fun delete() {
        val cardId = id ?: return
        viewModelScope.launch {
            when (val r = api.deleteCard(cardId)) {
                is MResult.Ok -> {
                    pim.forgetCard(cardId)
                    _state.update { it.copy(done = 0L) }
                }
                else -> _state.update { it.copy(message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun addEmail() = edit { it.copy(emails = it.emails + MCardEmail("", "home")) }
    fun addPhone() = edit { it.copy(phones = it.phones + MCardPhone("", "mobile")) }
    fun addAddress() = edit { it.copy(addresses = it.addresses + MCardAddress(type = "home")) }
}
