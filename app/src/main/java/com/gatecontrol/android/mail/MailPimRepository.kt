package com.gatecontrol.android.mail

import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonElement
import kotlinx.coroutines.CompletableDeferred
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

    /** The favourites (Mail 1.4.0, by their position); null: not known yet. */
    private val _favorites = MutableStateFlow<List<MFavorite>?>(null)
    val favorites: StateFlow<List<MFavorite>?> = _favorites.asStateFlow()

    /** The calendar widget's events (Mail 1.4.0); null: not known yet. */
    private val _upcoming = MutableStateFlow<MUpcoming?>(null)
    val upcoming: StateFlow<MUpcoming?> = _upcoming.asStateFlow()

    /** Whether the server has favourites / the widget's events (false: older than Mail 1.4.0 or switched off; null: not asked yet). */
    private val _favoritesSupported = MutableStateFlow<Boolean?>(null)
    val favoritesSupported: StateFlow<Boolean?> = _favoritesSupported.asStateFlow()
    private val _upcomingSupported = MutableStateFlow<Boolean?>(null)
    val upcomingSupported: StateFlow<Boolean?> = _upcomingSupported.asStateFlow()

    /** What Mail pins to Start on this device (favourite tiles, the calendar widget). */
    private val _startPins = MutableStateFlow(MailStartPins())
    val startPins: StateFlow<MailStartPins> = _startPins.asStateFlow()
    private val pinsLoaded = CompletableDeferred<Unit>()
    private val pinsMutex = Mutex()

    private var cardsVersion = 0L
    private val cardsMutex = Mutex()
    private var calJob: Job? = null
    private var booksJob: Job? = null

    init {
        scope.launch {
            try {
                cache.startPins()?.let { _startPins.value = it }
                cache.favorites()?.let { if (_favorites.value == null) _favorites.value = it }
                cache.upcoming()?.let { if (_upcoming.value == null) _upcoming.value = it }
            } finally {
                pinsLoaded.complete(Unit)
            }
        }
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

    // ─── Start: favourites and the calendar widget (Mail 1.4.0) ─────

    /** Whether [r] says the server has no such path (older than Mail 1.4.0) or the function is off. */
    private fun missing(r: MResult<*>): Boolean = r is MResult.Failed && (r.http == 404 || r.code == "feature_off" || r.code == "not_found")

    /** `GET cards/favorites` (the copy when offline); the tiles on Start follow (a contact no favourite any more leaves). */
    suspend fun refreshFavorites(): MResult<List<MFavorite>> = when (val r = api.favorites()) {
        is MResult.Ok -> {
            _favorites.value = r.value.first
            _favoritesSupported.value = true
            cache.putFavorites(r.value.second)
            updateStartPins { MailContactLogic.syncedPins(it, r.value.first) }
            MResult.Ok(r.value.first)
        }
        MResult.Offline -> {
            if (_favorites.value == null) cache.favorites()?.let { _favorites.value = it }
            r.notOk()
        }
        else -> {
            if (missing(r)) _favoritesSupported.value = false
            r.notOk()
        }
    }

    private var favJob: Job? = null

    fun refreshFavoritesSoon() {
        if (favJob?.isActive == true) return
        favJob = scope.launch { refreshFavorites() }
    }

    /** A favourite switched here (shown at once; the server's list follows). */
    fun putFavorite(c: MCard) {
        val on = c.favorite ?: return
        _cards.update { l -> l.map { if (it.id == c.id) it.copy(favorite = on) else it } }
        refreshFavoritesSoon()
    }

    /** `GET events/upcoming` (the copy when offline). */
    suspend fun refreshUpcoming(): MResult<MUpcoming> = when (val r = api.upcoming()) {
        is MResult.Ok -> {
            _upcoming.value = r.value.first
            _upcomingSupported.value = true
            cache.putUpcoming(r.value.second)
            MResult.Ok(r.value.first)
        }
        MResult.Offline -> {
            if (_upcoming.value == null) cache.upcoming()?.let { _upcoming.value = it }
            r.notOk()
        }
        else -> {
            if (missing(r)) _upcomingSupported.value = false
            r.notOk()
        }
    }

    private var upcomingJob: Job? = null

    fun refreshUpcomingSoon() {
        if (upcomingJob?.isActive == true) return
        upcomingJob = scope.launch { refreshUpcoming() }
    }

    /** The pins as stored (waits for the copy to be read). */
    suspend fun currentStartPins(): MailStartPins {
        pinsLoaded.await()
        return _startPins.value
    }

    /** Changes what Mail pins to Start; kept in the offline copy. */
    suspend fun updateStartPins(change: (MailStartPins) -> MailStartPins): MailStartPins {
        pinsLoaded.await()
        return pinsMutex.withLock {
            val before = _startPins.value
            val next = change(before)
            if (next != before) {
                _startPins.value = next
                cache.putStartPins(next)
            }
            next
        }
    }

    // ─── Microsoft ──────────────────────────────────────────────────

    /** The person goes to sign in again for [accountId] (the hint about the company's consent follows if it stays). */
    fun markRelogin(accountId: Long) = _relogins.update { it + accountId }

    // ─── live ───────────────────────────────────────────────────────

    /** `event: plugin` of Mail with type `calendar` / `contacts` (the `mail` ones go to [MailRepository]). */
    fun onPluginEvent(type: String, data: JsonElement?): Boolean {
        when (type) {
            "calendar" -> {
                val c = MailPimJson.calChanged(data)
                // the widget on Start: any change of the calendars may move its events
                if (_upcoming.value != null || _startPins.value.agenda) refreshUpcomingSoon()
                c ?: return true
                _calChanged.tryEmit(c)
                if (c.kind == MCalChanged.CALENDARS || c.kind == MCalChanged.CHANGED) refreshCalendarsSoon()
                return true
            }
            "contacts" -> {
                val c = MailPimJson.contactsChanged(data) ?: return true
                _contactsChanged.tryEmit(c)
                when (c.kind) {
                    MContactsChanged.CHANGED -> if (_cards.value.isNotEmpty()) syncCardsSoon()
                    // Mail 1.4.0: cards became one GC contact (the list), the favourites (the tiles), a hint (the contact's page)
                    MContactsChanged.MERGED -> if (_cards.value.isNotEmpty()) syncCardsSoon()
                    MContactsChanged.FAVORITES -> Unit
                    MContactsChanged.CONFLICTS -> if (_cards.value.isNotEmpty()) syncCardsSoon()
                    else -> refreshBooksSoon()
                }
                if (_favorites.value != null && (c.kind != MContactsChanged.CONFLICTS)) {
                    val favIds = _favorites.value.orEmpty().map { it.id }.toSet()
                    if (c.kind != MContactsChanged.CHANGED || c.contactIds.isEmpty() || c.contactIds.any { it in favIds }) refreshFavoritesSoon()
                }
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
        _favorites.value = null
        _upcoming.value = null
        _favoritesSupported.value = null
        _upcomingSupported.value = null
        _startPins.value = MailStartPins()
    }
}
