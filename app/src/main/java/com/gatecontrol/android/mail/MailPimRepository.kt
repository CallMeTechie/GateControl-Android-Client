package com.gatecontrol.android.mail

import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the calendar and contact screens share (contract D2–D7): the calendars with their overlays and settings, the
 * address books and groups, every contact as a summary (synced with `since`, searched offline), the realtime events
 * `calendar` and `contacts`, and the offline copy of it all ([MailCache]). Offline everything is read from the copy;
 * changing needs the server.
 */
@Singleton
class MailPimRepository @Inject constructor(
    val api: MailApi,
    private val cache: MailCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private val _calendars = MutableStateFlow<MCalendars?>(null)
    val calendars: StateFlow<MCalendars?> = _calendars.asStateFlow()

    private val _books = MutableStateFlow<MBooks?>(null)
    val books: StateFlow<MBooks?> = _books.asStateFlow()

    /** Every visible contact (summaries), sorted as the server sorts them. */
    private val _cards = MutableStateFlow<List<MCardSummary>>(emptyList())
    val cards: StateFlow<List<MCardSummary>> = _cards.asStateFlow()

    /** The last answer could not be had (the copy is shown): offline. */
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _calChanged = MutableSharedFlow<MCalChanged>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val calChanged: SharedFlow<MCalChanged> = _calChanged.asSharedFlow()

    private val _contactsChanged = MutableSharedFlow<MContactsChanged>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val contactsChanged: SharedFlow<MContactsChanged> = _contactsChanged.asSharedFlow()

    /** Microsoft accounts the person was sent to sign in again (a hint names the company's consent if it stays). */
    private val _relogins = MutableStateFlow<Set<Long>>(emptySet())
    val relogins: StateFlow<Set<Long>> = _relogins.asStateFlow()

    private var cardsVersion = 0L
    private val cardsMutex = Mutex()
    private var calJob: Job? = null
    private var booksJob: Job? = null

    init {
        scope.launch {
            if (_calendars.value == null) cache.calendars()?.let { if (_calendars.value == null) _calendars.value = it }
            if (_books.value == null) cache.books()?.let { if (_books.value == null) _books.value = it }
            if (_cards.value.isEmpty()) cache.cards()?.let { (list, version) ->
                if (_cards.value.isEmpty()) {
                    _cards.value = list
                    cardsVersion = version
                }
            }
        }
    }

    // ─── calendars ──────────────────────────────────────────────────

    /** `GET calendars` now (the copy when offline). */
    suspend fun refreshCalendars(): MResult<MCalendars> {
        val r = api.calendars()
        return when (r) {
            is MResult.Ok -> {
                _calendars.value = r.value.first
                _offline.value = false
                cache.putCalendars(r.value.second)
                MResult.Ok(r.value.first)
            }
            MResult.Offline -> {
                _offline.value = true
                if (_calendars.value == null) cache.calendars()?.let { _calendars.value = it }
                r.notOk()
            }
            else -> r.notOk()
        }
    }

    fun refreshCalendarsSoon() {
        if (calJob?.isActive == true) return
        calJob = scope.launch { refreshCalendars() }
    }

    /** A calendar changed here (shown at once; the server's answer replaces it). */
    fun putCalendar(c: MCalendar) {
        _calendars.update { cur -> cur?.copy(calendars = cur.calendars.map { if (it.id == c.id) c else it }.let { l -> if (l.any { it.id == c.id }) l else l + c }) }
    }

    fun dropCalendar(id: Long) {
        _calendars.update { cur -> cur?.copy(calendars = cur.calendars.filter { it.id != id }) }
    }

    fun putOverlay(o: MOverlay) {
        _calendars.update { cur -> cur?.copy(overlays = cur.overlays.map { if (it.id == o.id) o else it }) }
    }

    fun putPrefs(p: MCalPrefs) {
        _calendars.update { cur -> cur?.copy(prefs = p) }
    }

    /**
     * The occurrences of `[from, to)` (one call per 62 days): the server's when it answers (kept for offline when the
     * range is near today), else the copy of these days.
     */
    suspend fun events(from: Long, to: Long, keep: Boolean): MResult<MEvents> {
        val items = ArrayList<MOccurrence>()
        val overlays = ArrayList<MOverlayItem>()
        val pending = LinkedHashSet<Long>()
        var t = from
        while (t < to) {
            val end = minOf(to, t + MailPimLogic.RANGE_DAYS * MailPimLogic.DAY_MS)
            when (val r = api.events(t, end)) {
                is MResult.Ok -> {
                    items += r.value.first.items
                    overlays += r.value.first.overlays
                    pending += r.value.first.pending
                    _offline.value = false
                    if (keep) cache.putEvents(t, end, r.value.second)
                }
                MResult.Offline -> {
                    _offline.value = true
                    val copy = cache.events(from, to) ?: return r.notOk()
                    return MResult.Ok(copy)
                }
                else -> return r.notOk()
            }
            t = end
        }
        return MResult.Ok(MEvents(items.distinctBy { it.key }, overlays.distinctBy { it.key }, pending.toList()))
    }

    /** One event (the copy of an opened one when offline). */
    suspend fun event(id: Long, occurrence: String?): MResult<MEvent> = when (val r = api.event(id, occurrence)) {
        is MResult.Ok -> {
            cache.putEvent(id, occurrence, r.value.second)
            MResult.Ok(r.value.first)
        }
        MResult.Offline -> cache.event(id, occurrence)?.let { MResult.Ok(it) } ?: r.notOk()
        else -> r.notOk()
    }

    // ─── contacts ───────────────────────────────────────────────────

    suspend fun refreshBooks(): MResult<MBooks> {
        val r = api.addressbooks()
        return when (r) {
            is MResult.Ok -> {
                _books.value = r.value.first
                _offline.value = false
                cache.putBooks(r.value.second)
                MResult.Ok(r.value.first)
            }
            MResult.Offline -> {
                _offline.value = true
                if (_books.value == null) cache.books()?.let { _books.value = it }
                r.notOk()
            }
            else -> r.notOk()
        }
    }

    fun refreshBooksSoon() {
        if (booksJob?.isActive == true) return
        booksJob = scope.launch { refreshBooks() }
    }

    fun putBooks(b: MBooks) {
        _books.value = b
    }

    /**
     * Every contact: all pages the first time, afterwards only what changed since the last version (`since`), the
     * deleted ones taken out. Kept for offline.
     */
    suspend fun syncCards(full: Boolean = false): MResult<List<MCardSummary>> = cardsMutex.withLock {
        val since = if (full || _cards.value.isEmpty()) null else cardsVersion
        val got = ArrayList<MCardSummary>()
        var deleted = emptyList<Long>()
        var cursor: String? = null
        var version = cardsVersion
        var pages = 0
        do {
            when (val r = api.cards(cursor = cursor, since = since, limit = 500)) {
                is MResult.Ok -> {
                    got += r.value.first.items
                    if (cursor == null) deleted = r.value.first.deleted
                    version = r.value.first.version
                    cursor = r.value.first.next
                    _offline.value = false
                }
                MResult.Offline -> {
                    _offline.value = true
                    return@withLock r.notOk()
                }
                else -> return@withLock r.notOk()
            }
            pages++
        } while (cursor != null && pages < 40)
        val list = if (since == null) got else MailPimLogic.applyCards(_cards.value, got, deleted.toSet())
        _cards.value = list
        cardsVersion = version
        cache.putCards(list, version)
        MResult.Ok(list)
    }

    fun syncCardsSoon(full: Boolean = false) {
        scope.launch { syncCards(full) }
    }

    /** One contact (the copy of an opened one when offline). */
    suspend fun card(id: Long): MResult<MCard> = when (val r = api.card(id)) {
        is MResult.Ok -> {
            cache.putCard(id, r.value.second)
            MResult.Ok(r.value.first)
        }
        MResult.Offline -> cache.card(id)?.let { MResult.Ok(it) } ?: r.notOk()
        else -> r.notOk()
    }

    /** A contact's photo: the copy (LRU, 500), else the server's. */
    suspend fun photo(id: Long, etag: String): MPhoto? {
        cache.photo(id, etag)?.let { return it }
        val p = api.cardPhoto(id).valueOrNull() ?: return null
        cache.putPhoto(id, etag, p)
        return p
    }

    fun forgetCard(id: Long) {
        _cards.update { l -> l.filter { it.id != id } }
        scope.launch { cache.forgetCard(id) }
    }

    // ─── Microsoft ──────────────────────────────────────────────────

    /** The person goes to sign in again for [accountId] (the hint about the company's consent follows if it stays). */
    fun markRelogin(accountId: Long) = _relogins.update { it + accountId }

    // ─── live ───────────────────────────────────────────────────────

    /** `event: plugin` of Mail with type `calendar` / `contacts` (the `mail` ones go to [MailRepository]). */
    fun onPluginEvent(type: String, data: JsonElement?): Boolean {
        when (type) {
            "calendar" -> {
                val c = MailPimJson.calChanged(data) ?: return true
                _calChanged.tryEmit(c)
                if (c.kind == MCalChanged.CALENDARS || c.kind == MCalChanged.CHANGED) refreshCalendarsSoon()
                return true
            }
            "contacts" -> {
                val c = MailPimJson.contactsChanged(data) ?: return true
                _contactsChanged.tryEmit(c)
                if (c.kind != MContactsChanged.CHANGED) refreshBooksSoon()
                if (c.kind == MContactsChanged.CHANGED && _cards.value.isNotEmpty()) syncCardsSoon()
                return true
            }
        }
        return false
    }

    /** (Re)connected: what changed meanwhile. */
    fun onStreamOpen() {
        if (_calendars.value != null) _calChanged.tryEmit(MCalChanged(MCalChanged.CALENDARS))
        if (_books.value != null) _contactsChanged.tryEmit(MContactsChanged(MContactsChanged.ADDRESSBOOKS))
    }

    /** The copy is gone ([MailRepository.forget]). */
    fun forget() {
        _calendars.value = null
        _books.value = null
        _cards.value = emptyList()
        cardsVersion = 0L
        _relogins.value = emptySet()
    }
}
