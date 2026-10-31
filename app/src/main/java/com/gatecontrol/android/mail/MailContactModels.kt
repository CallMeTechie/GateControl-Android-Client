package com.gatecontrol.android.mail

/**
 * Contacts of the Mail plugin (since Mail 1.2.0, contract C3 "Kontakte", path `cards`): address books, groups, the
 * contacts as a list and one by one, the sender lookup, duplicates, vCard import and export. Read by [MailPimJson].
 */

data class MAddressbook(
    val id: Long,
    /** local | graph | carddav */
    val source: String,
    val accountId: Long?,
    val name: String,
    val writable: Boolean,
    val visible: Boolean,
    val isDefault: Boolean,
    val count: Int,
    val state: String,
    val errorCode: String?,
    val syncedAt: Long?,
) {
    val local: Boolean get() = source == SOURCE_LOCAL

    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_GRAPH = "graph"
        const val SOURCE_CARDDAV = "carddav"
    }
}

/** A distribution list of the person (GateControl only, across sources). */
data class MGroup(val id: Long, val name: String, val color: String?, val count: Int)

data class MCardPrefs(
    /** family | given */
    val sort: String,
    /** given_family | family_given */
    val show: String,
    val defaultAddressbookId: Long?,
    val birthdays: Boolean,
) {
    companion object {
        val DEFAULT = MCardPrefs("family", "given_family", null, true)
    }
}

/** `GET addressbooks`. */
data class MBooks(val items: List<MAddressbook>, val groups: List<MGroup>, val prefs: MCardPrefs) {
    fun book(id: Long?): MAddressbook? = id?.let { b -> items.firstOrNull { it.id == b } }
}

/** A contact in a list (`CardSummary`). */
data class MCardSummary(
    val id: Long,
    val addressbookId: Long,
    val name: String,
    val sortKey: String,
    /** `A` … `Z` or `#` */
    val letter: String,
    val company: String?,
    val title: String?,
    val emails: List<String>,
    val phone: String?,
    val photo: Boolean,
    val photoEtag: String?,
    val groups: List<Long>,
    val linked: List<Long>,
    val gcUserId: Long?,
    val updatedAt: Long?,
    /** Since Mail 1.4.0 (null before: the star is not offered). */
    val favorite: Boolean? = null,
    /** Mail 1.4.0: open conflicts of this GC contact with its linked cards (the dot in the list). */
    val conflicts: Int = 0,
    /** Mail 1.4.0: a linked card (a list of one account's address book): the GC contact that stands for it. */
    val masterId: Long? = null,
    /** Mail 1.4.0: the address books this contact and its linked cards come from (the dots in the list). */
    val sources: List<MSourceRef> = emptyList(),
)

/** Where a contact of the list comes from (Mail 1.4.0, `CardSummary.sources`). */
data class MSourceRef(val addressbookId: Long, val source: String, val accountId: Long?)

/** `GET cards`. */
data class MCardPage(val items: List<MCardSummary>, val next: String?, val version: Long, val deleted: List<Long>)

data class MCardName(
    val display: String = "",
    val given: String = "",
    val family: String = "",
    val middle: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val nickname: String? = null,
)

data class MCardEmail(val value: String, /** home | work | other */ val type: String = "other", val primary: Boolean = false)

data class MCardPhone(val value: String, /** mobile | home | work | fax | other */ val type: String = "mobile")

data class MCardAddress(
    /** home | work | other */
    val type: String = "home",
    val street: String = "",
    val postalCode: String = "",
    val city: String = "",
    val region: String = "",
    val country: String = "",
) {
    val empty: Boolean get() = listOf(street, postalCode, city, region, country).all { it.isBlank() }
}

data class MCardLink(val id: Long, val addressbookId: Long, val source: String)

/** One contact (`Card`); also the fields of a new one and of `cards/draft` (then [id] is 0). */
data class MCard(
    val id: Long = 0,
    val addressbookId: Long = 0,
    val source: String = MAddressbook.SOURCE_LOCAL,
    val readOnly: Boolean = false,
    val etag: String? = null,
    val name: MCardName = MCardName(),
    val emails: List<MCardEmail> = emptyList(),
    val phones: List<MCardPhone> = emptyList(),
    val company: String? = null,
    val department: String? = null,
    val title: String? = null,
    val addresses: List<MCardAddress> = emptyList(),
    /** `YYYY-MM-DD` or `--MM-DD` (year unknown) */
    val birthday: String? = null,
    val note: String = "",
    val urls: List<String> = emptyList(),
    val photoEtag: String? = null,
    val photoMime: String? = null,
    val groups: List<Long> = emptyList(),
    val linked: List<MCardLink> = emptyList(),
    val gcUserId: Long? = null,
    val updatedAt: Long? = null,
    /** Since Mail 1.4.0 (null before: no star, no main number). */
    val favorite: Boolean? = null,
    /** Mail 1.4.0: the main number (chosen, else the first mobile, else the first). */
    val mainPhone: MCardPhone? = null,
    val mainPhoneChosen: Boolean = false,
    /** Mail 1.4.0: a linked card shows this GC contact instead. */
    val masterId: Long? = null,
    /** Mail 1.4.0: values of linked cards that differ from this contact's. */
    val conflicts: List<MCardConflict> = emptyList(),
    /** Mail 1.4.0: the card itself first, then its linked cards. Empty before 1.4.0. */
    val sources: List<MSource> = emptyList(),
) {
    val label: String get() = name.display.ifBlank { listOf(name.given, name.family).filter { it.isNotBlank() }.joinToString(" ") }
        .ifBlank { company ?: emails.firstOrNull()?.value ?: "" }

    val photo: Boolean get() = photoEtag != null

    companion object {
        val EMAIL_TYPES = listOf("work", "home", "other")
        val PHONE_TYPES = listOf("mobile", "work", "home", "fax", "other")
        val ADDRESS_TYPES = listOf("home", "work", "other")
    }
}

/** `GET cards/lookup?email=` (the sender's page). */
data class MLookup(val card: MCard?, val personUserId: Long?, val personName: String?, val recentName: String?, val sent: Int, val received: Int, val lastAt: Long?)

/** `GET cards/<id>/activity`. */
data class MActivity(val mails: List<MSummary>, val events: List<MOccurrence>)

/** `GET cards/draft`: the fields from a mail and the contact that has the address already. */
data class MCardDraft(val draft: MCard, val existing: MCard?)

/** `GET cards/duplicates`: contacts that may be one person. */
data class MDuplicate(
    val key: String,
    /** email | phone | name */
    val reason: String,
    val cards: List<MCardSummary>,
    /** Mail 1.4.0: the server merges certain ones by itself (null: an older server). */
    val certain: Boolean? = null,
    /** Mail 1.4.0 (`details=1`): each card in full with where it comes from. */
    val details: List<MDuplicateCard> = emptyList(),
    /** Mail 1.4.0 (`details=1`): what merging gives. */
    val preview: MPreview? = null,
)

/** One card of a duplicate in full and its address book / account. */
data class MDuplicateCard(val card: MCard, val source: MSource?)

/** A card of a contact (Mail 1.4.0 `Source`): the card's id and the address book (and account) it is in. */
data class MSource(
    val id: Long,
    val addressbookId: Long,
    val addressbook: String,
    /** local | graph | carddav */
    val source: String,
    val accountId: Long?,
    /** The account's address. */
    val account: String?,
) {
    val local: Boolean get() = source == MAddressbook.SOURCE_LOCAL
}

/** A value of a contact's field: a number, a postal address or a text (company, department, title, birthday). */
sealed interface MFieldValue {
    data class Phone(val phone: MCardPhone) : MFieldValue
    data class Address(val address: MCardAddress) : MFieldValue
    data class Text(val text: String) : MFieldValue
}

/**
 * A value of a linked card that differs from the GC contact's (Mail 1.4.0 `Conflict`): another mobile number, another
 * company … [replaces]: the contact's value it would replace (null: it would be added).
 */
data class MCardConflict(
    val id: Long,
    /** phones | addresses | company | department | title | birthday */
    val field: String,
    val type: String?,
    val value: MFieldValue,
    val replaces: MFieldValue?,
    val source: MSource?,
    val at: Long?,
) {
    companion object {
        const val PHONES = "phones"
        const val ADDRESSES = "addresses"
        const val COMPANY = "company"
        const val DEPARTMENT = "department"
        const val TITLE = "title"
        const val BIRTHDAY = "birthday"
        val FIELDS = setOf(PHONES, ADDRESSES, COMPANY, DEPARTMENT, TITLE, BIRTHDAY)
    }
}

/** What merging cards gives (Mail 1.4.0 `Preview`): the fields of the GC contact, its cards, a single value that loses. */
data class MPreview(
    /** The GC contact that stays (null: a new one is made). */
    val id: Long?,
    val card: MCard,
    val photoFrom: Long?,
    val sources: List<MSource>,
    /** A single field that loses (another company …): a conflict at the contact afterwards. */
    val losers: List<MPreviewLoser>,
)

data class MPreviewLoser(val field: String, val value: String, val source: MSource?)

/** A merge in the protocol (Mail 1.4.0 `Merge`, "Automatisch zusammengeführt"). */
data class MMerge(
    val id: Long,
    val masterId: Long,
    /** auto | manual | migrated */
    val mode: String,
    /** The GC contact was made by the merge (undoing removes it unless kept). */
    val created: Boolean,
    /** email | phone | name | link | null */
    val reason: String?,
    val at: Long,
    val undoneAt: Long?,
    val cards: List<MMergeCard>,
) {
    val automatic: Boolean get() = mode == AUTO || mode == MIGRATED

    companion object {
        const val AUTO = "auto"
        const val MANUAL = "manual"
        const val MIGRATED = "migrated"
    }
}

data class MMergeCard(val id: Long, val name: String, val addressbookId: Long, val addressbook: String, val source: String, val removed: Boolean)

/** `POST cards/merges/<id>/undo`, `POST cards/<id>/unmerge`. */
data class MUnmerge(val card: MCard?, val unlinked: List<Long>, val restored: List<Long>)

/** A linked card the server wrote a change back to (Mail 1.4.0 `writeBack`). */
data class MWriteBack(val id: Long, val addressbookId: Long, val ok: Boolean, val code: String?)

/** `PATCH cards/<id>`: the contact and where the change went back to (empty before Mail 1.4.0). */
data class MCardSaved(val card: MCard, val writeBack: List<MWriteBack>)

/** A favourite (Mail 1.4.0 `GET cards/favorites`): the tile on Start. */
data class MFavorite(
    val id: Long,
    val name: String,
    val photoEtag: String?,
    /** The main number (null: none; a tap opens the contact). */
    val phone: MCardPhone?,
    val email: String?,
)

/** An event of the calendar widget on Start (Mail 1.4.0 `GET events/upcoming`). */
data class MUpcomingItem(
    val key: String,
    val eventId: Long?,
    val calendarId: Long?,
    val overlayId: String?,
    val color: String?,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    /** The day it is shown on (`YYYY-MM-DD`, the person's time zone). */
    val day: String,
    val location: String?,
    val tentative: Boolean,
) {
    /** The occurrence of a series (`<eventId>:<occ>`), null for a single event. */
    val occurrence: String? get() = eventId?.let { e -> key.takeIf { it.startsWith("$e:") }?.substringAfter(':')?.takeIf { it.isNotEmpty() } }
}

/** `GET events/upcoming`. */
data class MUpcoming(val timeZone: String?, val today: String, val items: List<MUpcomingItem>, val more: Int)

data class MGroupMember(val id: Long, val contactId: Long?, val name: String, val email: String?)

/** `GET groups/<id>`. */
data class MGroupDetail(val group: MGroup, val members: List<MGroupMember>)

/** `POST cards/import`. */
data class MImport(val imported: Int, val updated: Int, val skipped: Int)

/** `GET cards/export`: the vCard file. */
class MExport(val name: String, val mime: String, val bytes: ByteArray)

/** A contact's photo (`GET cards/<id>/photo`). */
class MPhoto(val mime: String, val bytes: ByteArray)

/**
 * Realtime event `contacts`: `changed` (an address book, [contactIds] when known), `addressbooks`, `groups`; since
 * Mail 1.4.0 `merged` ([masterId] stands for [contactIds]), `conflicts` and `favorites`.
 */
data class MContactsChanged(val kind: String, val addressbookId: Long? = null, val contactIds: List<Long> = emptyList(), val masterId: Long? = null) {
    companion object {
        const val CHANGED = "changed"
        const val ADDRESSBOOKS = "addressbooks"
        const val GROUPS = "groups"
        const val MERGED = "merged"
        const val CONFLICTS = "conflicts"
        const val FAVORITES = "favorites"
        val KINDS = setOf(CHANGED, ADDRESSBOOKS, GROUPS, MERGED, CONFLICTS, FAVORITES)
    }
}

/** How merging picks the single fields (`pick`): the contact whose value stays. */
data class MMergePick(
    val name: Long? = null,
    val company: Long? = null,
    val title: Long? = null,
    val birthday: Long? = null,
    val photo: Long? = null,
)
