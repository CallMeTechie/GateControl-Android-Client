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
)

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
data class MDuplicate(val key: String, /** email | phone | name */ val reason: String, val cards: List<MCardSummary>)

data class MGroupMember(val id: Long, val contactId: Long?, val name: String, val email: String?)

/** `GET groups/<id>`. */
data class MGroupDetail(val group: MGroup, val members: List<MGroupMember>)

/** `POST cards/import`. */
data class MImport(val imported: Int, val updated: Int, val skipped: Int)

/** `GET cards/export`: the vCard file. */
class MExport(val name: String, val mime: String, val bytes: ByteArray)

/** A contact's photo (`GET cards/<id>/photo`). */
class MPhoto(val mime: String, val bytes: ByteArray)

/** Realtime event `contacts`: `changed` (an address book, [contactIds] when known), `addressbooks`, `groups`. */
data class MContactsChanged(val kind: String, val addressbookId: Long? = null, val contactIds: List<Long> = emptyList()) {
    companion object {
        const val CHANGED = "changed"
        const val ADDRESSBOOKS = "addressbooks"
        const val GROUPS = "groups"
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
