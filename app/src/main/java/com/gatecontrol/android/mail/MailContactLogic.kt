package com.gatecontrol.android.mail

import com.gatecontrol.android.layout.StartExtras
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A favourite pinned to Start as a tile, with a copy of what it shows (offline too). */
data class StartContactPin(val id: Long, val name: String, val photoEtag: String? = null, val phone: String? = null)

/** What Mail pins to Start on this device (Mail 1.4.0): favourites as tiles and the calendar widget. */
data class MailStartPins(val contacts: List<StartContactPin> = emptyList(), val agenda: Boolean = false) {
    val extras: StartExtras get() = StartExtras(contacts.map { it.id }, agenda)

    fun has(id: Long): Boolean = contacts.any { it.id == id }
}

/** One day of the calendar widget: its events in the server's order. */
data class AgendaDay(val day: LocalDate, val today: Boolean, val items: List<MUpcomingItem>)

/** What the calendar widget shows: today and tomorrow (a day without events is left out), [more] beyond. */
data class AgendaView(val days: List<AgendaDay>, val more: Int) {
    val empty: Boolean get() = days.isEmpty()
}

/** What a tap on a favourite's tile does: call [tel] (a `tel:` uri) or, without a number, open the contact. */
sealed interface TileTap {
    data class Call(val tel: String) : TileTap
    data class Open(val id: Long) : TileTap
}

/**
 * The rules of the GC contact as the master (Mail 1.4.0, pure, unit-tested): which contacts the list shows and with
 * which dots, the hints of differing values, the preview of a merge, favourites and the main number, the tiles and
 * the calendar widget on Start, and what a change wrote back to a provider.
 */
object MailContactLogic {

    /** Favourites the server keeps per person. */
    const val FAVORITES_MAX = 50

    /** Favourite tiles on Start (like the plugins' buttons). */
    const val PINS_MAX = 12

    const val ACTION_CALL = "android.intent.action.CALL"
    const val ACTION_DIAL = "android.intent.action.DIAL"

    // ─── the list ───────────────────────────────────────────────────

    /** The list of all contacts: GC contacts and loose cards; a linked card stands under its GC contact. */
    fun listed(items: List<MCardSummary>): List<MCardSummary> = items.filter { it.masterId == null }

    /**
     * The accounts a contact of the list comes from (one dot each, in their order): from `sources` (Mail 1.4.0),
     * else from its own address book. Own contacts (GC address book) have no dot.
     */
    fun sourceAccounts(c: MCardSummary, bookAccount: (Long) -> Long?): List<Long> {
        val ids = if (c.sources.isNotEmpty()) c.sources.map { it.accountId ?: bookAccount(it.addressbookId) } else listOf(bookAccount(c.addressbookId))
        return ids.filterNotNull().distinct()
    }

    /** Whether the server has the master model (Mail 1.4.0): a contact names its sources or its favourite flag. */
    fun masterModel(c: MCard): Boolean = c.sources.isNotEmpty() || c.favorite != null

    // ─── hints of differing values ──────────────────────────────────

    /** A value as one line: a number, a postal address (street, postal code and city), a text. */
    fun valueText(v: MFieldValue?): String = when (v) {
        is MFieldValue.Phone -> v.phone.value
        is MFieldValue.Address -> listOf(v.address.street, listOf(v.address.postalCode, v.address.city).filter { it.isNotBlank() }.joinToString(" "), v.address.country)
            .filter { it.isNotBlank() }.joinToString(", ")
        is MFieldValue.Text -> v.text
        null -> ""
    }

    /**
     * What a hint names: `mobile` / `phone` (another number), `address`, `company`, `department`, `title`, `birthday`.
     * A number counts as mobile by its type.
     */
    fun hintKind(c: MCardConflict): String = when (c.field) {
        MCardConflict.PHONES -> if ((c.type ?: (c.value as? MFieldValue.Phone)?.phone?.type) == "mobile") "mobile" else "phone"
        MCardConflict.ADDRESSES -> "address"
        else -> c.field
    }

    /** "Zusätzlich" where it fits: a number or a postal address that would replace one (else it is added anyway). */
    fun canAdd(c: MCardConflict): Boolean = c.replaces != null && (c.field == MCardConflict.PHONES || c.field == MCardConflict.ADDRESSES)

    /** The mode of "Übernehmen" ([add]: "Zusätzlich"). */
    fun acceptMode(c: MCardConflict, add: Boolean): String = if (add || c.replaces == null) "add" else "replace"

    /** The provider's name of a source: Outlook for Microsoft, the address book's name for CardDAV, null for own contacts. */
    fun providerName(source: String, addressbook: String?): String? = when (source) {
        MAddressbook.SOURCE_GRAPH -> "Outlook"
        MAddressbook.SOURCE_CARDDAV -> addressbook?.takeIf { it.isNotBlank() } ?: "CardDAV"
        else -> null
    }

    /** Where a value comes from: "Outlook (ma@example.com)", the address book's name, or the account alone. */
    fun originLabel(s: MSource?): String {
        s ?: return ""
        val provider = providerName(s.source, s.addressbook) ?: s.addressbook
        return when {
            provider.isBlank() -> s.account.orEmpty()
            s.account.isNullOrBlank() -> provider
            else -> "$provider (${s.account})"
        }
    }

    // ─── the preview of a merge ─────────────────────────────────────

    /**
     * What merging gives when the server sends no preview (before Mail 1.4.0): the GC card (else the first) keeps
     * name and single fields, the lists of all are joined ([MailPimLogic.merged]).
     */
    fun localPreview(cards: List<MCard>): MCard? {
        if (cards.size < 2) return null
        val keep = cards.firstOrNull { it.source == MAddressbook.SOURCE_LOCAL } ?: cards.first()
        return MailPimLogic.merged(cards, keep.id)
    }

    /**
     * The values of a card of a duplicate that the others lack (shown as new in its card): numbers, addresses and
     * postal addresses by their comparing key.
     */
    fun ownValues(card: MCard, others: List<MCard>): Set<String> {
        val phones = others.flatMap { o -> o.phones.map { MailPimLogic.phoneKey(it.value) } }.toSet()
        val emails = others.flatMap { o -> o.emails.map { it.value.trim().lowercase() } }.toSet()
        val streets = others.flatMap { o -> o.addresses.map(::addressKey) }.toSet()
        return buildSet {
            card.phones.filter { MailPimLogic.phoneKey(it.value) !in phones }.forEach { add("p:" + MailPimLogic.phoneKey(it.value)) }
            card.emails.filter { it.value.trim().lowercase() !in emails }.forEach { add("e:" + it.value.trim().lowercase()) }
            card.addresses.filter { addressKey(it) !in streets }.forEach { add("a:" + addressKey(it)) }
        }
    }

    fun addressKey(a: MCardAddress): String = "${a.street.trim()}|${a.postalCode.trim()}|${a.city.trim()}".lowercase()

    // ─── favourites and the main number ─────────────────────────────

    /**
     * The favourites' order after one is switched [on] or off (the end of the row), null when [on] would pass
     * [FAVORITES_MAX].
     */
    fun toggled(current: List<Long>, id: Long, on: Boolean): List<Long>? = when {
        on && id in current -> current
        on && current.size >= FAVORITES_MAX -> null
        on -> current + id
        else -> current - id
    }

    /** [current] with [id] one place earlier ([delta] -1) or later (+1); unchanged at the ends. */
    fun moved(current: List<Long>, id: Long, delta: Int): List<Long> {
        val from = current.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in current.indices) return current
        return current.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** The main number: the one chosen (compared by its digits), else the first mobile, else the first. */
    fun mainPhoneOf(phones: List<MCardPhone>, chosen: String?): MCardPhone? {
        val key = chosen?.let(MailPimLogic::phoneKey)?.takeIf { it.isNotEmpty() }
        return key?.let { k -> phones.firstOrNull { MailPimLogic.phoneKey(it.value) == k } }
            ?: phones.firstOrNull { it.type == "mobile" } ?: phones.firstOrNull()
    }

    /** Whether [p] is the contact's main number (the server's choice, else the rule of [mainPhoneOf]). */
    fun isMain(c: MCard, p: MCardPhone): Boolean {
        val main = c.mainPhone?.takeIf { m -> c.phones.any { MailPimLogic.phoneKey(it.value) == MailPimLogic.phoneKey(m.value) } } ?: mainPhoneOf(c.phones, null)
        main ?: return false
        val k = MailPimLogic.phoneKey(main.value)
        return if (k.isNotEmpty()) MailPimLogic.phoneKey(p.value) == k else p.value == main.value
    }

    /** "Als Hauptnummer" is offered for every number but the main one, and only with more than one. */
    fun canChooseMain(c: MCard, p: MCardPhone): Boolean = c.favorite != null && c.phones.size > 1 && !isMain(c, p)

    // ─── Start: favourite tiles ─────────────────────────────────────

    /** A pin from a favourite as the server lists it. */
    fun pinOf(f: MFavorite): StartContactPin = StartContactPin(f.id, f.name, f.photoEtag, f.phone?.value)

    /** A pin from the contact itself (pinning from its page). */
    fun pinOf(c: MCard): StartContactPin = StartContactPin(c.id, c.label, c.photoEtag, (c.mainPhone ?: mainPhoneOf(c.phones, null))?.value)

    /**
     * The pins after the favourites came: a pin takes the favourite's name, photo and number; a contact that is no
     * favourite any more leaves Start. The order stays.
     */
    fun syncedPins(pins: MailStartPins, favorites: List<MFavorite>): MailStartPins {
        val byId = favorites.associateBy { it.id }
        return pins.copy(contacts = pins.contacts.mapNotNull { p -> byId[p.id]?.let(::pinOf) })
    }

    /** [pins] with [pin] on Start (at the end; at most [PINS_MAX]) or off it. */
    fun withPin(pins: MailStartPins, pin: StartContactPin, on: Boolean): MailStartPins = when {
        !on -> pins.copy(contacts = pins.contacts.filter { it.id != pin.id })
        pins.has(pin.id) -> pins.copy(contacts = pins.contacts.map { if (it.id == pin.id) pin else it })
        else -> pins.copy(contacts = (pins.contacts + pin).take(PINS_MAX))
    }

    /** A tap on a favourite's tile: its main number, else (none) the contact. */
    fun tap(pin: StartContactPin): TileTap = pin.phone?.let(MailPimLogic::telUri)?.let { TileTap.Call(it) } ?: TileTap.Open(pin.id)

    /**
     * How a tile calls: straight away (`ACTION_CALL`) only while the app holds the permission to place calls (the
     * person allowed it), else the dialer with the number (`ACTION_DIAL`, no permission needed).
     */
    fun callAction(callPermission: Boolean): String = if (callPermission) ACTION_CALL else ACTION_DIAL

    /** The pins as kept in the offline copy (read without reflection). */
    fun pinsJson(p: MailStartPins): JsonObject = JsonObject().apply {
        addProperty("agenda", p.agenda)
        add("contacts", JsonArray().apply {
            p.contacts.forEach { c ->
                add(JsonObject().apply {
                    addProperty("id", c.id)
                    addProperty("name", c.name)
                    c.photoEtag?.let { addProperty("photoEtag", it) }
                    c.phone?.let { addProperty("phone", it) }
                })
            }
        })
    }

    fun pinsOf(o: JsonObject?): MailStartPins {
        o ?: return MailStartPins()
        val agenda = runCatching { o.get("agenda")?.takeIf { it.isJsonPrimitive }?.asBoolean }.getOrNull() ?: false
        val contacts = o.get("contacts")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
            val x = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun str(k: String) = x.get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
            val id = str("id")?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            StartContactPin(id, str("name").orEmpty().take(200), str("photoEtag")?.takeIf { it.isNotEmpty() && it.length <= 200 }, str("phone")?.takeIf { it.isNotBlank() }?.take(64))
        }.orEmpty().distinctBy { it.id }.take(PINS_MAX)
        return MailStartPins(contacts, agenda)
    }

    // ─── Start: the calendar widget ─────────────────────────────────

    /**
     * What the widget shows of [u] at [now] in [zone]: events not over yet, by their day (today and tomorrow of
     * [now]; an all-day event still running counts for today), the ones of a day in the server's order. An offline
     * copy from yesterday thus shows what is left of it.
     */
    fun agenda(u: MUpcoming, now: Long, zone: ZoneId): AgendaView {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val tomorrow = today.plusDays(1)
        val left = u.items.filter { it.end > now || (it.allDay && it.end >= now) }
        val byDay = LinkedHashMap<LocalDate, MutableList<MUpcomingItem>>()
        for (x in left) {
            val day = MailPimLogic.date(x.day) ?: continue
            val shown = if (day.isBefore(today)) today else day
            if (shown.isAfter(tomorrow)) continue
            byDay.getOrPut(shown) { ArrayList() } += x
        }
        val days = byDay.entries.sortedBy { it.key }.map { (d, items) ->
            AgendaDay(d, d == today, items.sortedWith(compareBy({ !it.allDay }, { it.start })))
        }
        return AgendaView(days, u.more)
    }

    /** When the widget asks again by itself: the next midnight in [zone] (a new day, new events). */
    fun nextDay(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Whether the widget's copy is of another day than [now] (it is asked for again). */
    fun stale(u: MUpcoming, now: Long, zone: ZoneId): Boolean = MailPimLogic.date(u.today) != Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    // ─── a change written back ──────────────────────────────────────

    /**
     * The providers a change went back to ([ok]) or not ([failed]), by name (Outlook, the CardDAV address book), each
     * once. [book] gives an address book's source and name.
     */
    fun writeBack(list: List<MWriteBack>, book: (Long) -> MAddressbook?): Pair<List<String>, List<String>> {
        fun name(w: MWriteBack): String? = book(w.addressbookId)?.let { providerName(it.source, it.name) ?: it.name }
        val ok = list.filter { it.ok }.mapNotNull(::name).distinct()
        val failed = list.filter { !it.ok }.mapNotNull(::name).distinct()
        return ok to failed
    }
}
