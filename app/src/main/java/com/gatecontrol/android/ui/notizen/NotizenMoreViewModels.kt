package com.gatecontrol.android.ui.notizen

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NMsAccount
import com.gatecontrol.android.notizen.NMsConsent
import com.gatecontrol.android.notizen.NMsLink
import com.gatecontrol.android.notizen.NMsList
import com.gatecontrol.android.notizen.NMsPreview
import com.gatecontrol.android.notizen.NPrefs
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NSourcePrefs
import com.gatecontrol.android.notizen.NSourceStatus
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.notizen.NotizenRepository
import com.gatecontrol.android.notizen.NotizenVaultSession
import com.gatecontrol.android.notizen.Nsx
import com.gatecontrol.android.notizen.NsxImport
import com.gatecontrol.android.notizen.UriNsxSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * „Einstellungen“: per source on/off and its own choices, the groups to an area, „Jetzt abgleichen“; the times of the
 * quick entry; with plugin 1.2.0 „Mein Tag“, „Beim Öffnen“ and the pushes.
 */
@HiltViewModel
class NotizenSourcesViewModel @Inject constructor(
    private val repo: NotizenRepository,
    private val geofences: com.gatecontrol.android.notizen.NotizenGeofences,
) : ViewModel() {
    data class Ui(
        val me: NMe? = null,
        val prefs: NPrefs = NPrefs(),
        val sources: List<NSourceStatus> = emptyList(),
        val loading: Boolean = true,
        val offline: Boolean = false,
        /** Plugin 1.5.0 (`places`): „Meine Orte“, the places of the family, what is remembered for which kind. */
        val places: com.gatecontrol.android.notizen.NPlaces = com.gatecontrol.android.notizen.NPlaces(),
    )

    // ── Orte (plugin 1.5.0) ─────────────────────────────────

    /** Can this phone remind at a place (Play services, the location also in the background). */
    fun placeReadiness() = geofences.readiness()

    /** The location was just allowed: the places of my tasks become geofences now. */
    fun placePermissionChanged() = geofences.syncSoon()

    suspend fun position(): Pair<Double, Double>? = geofences.position()

    private fun loadPlaces() {
        viewModelScope.launch {
            if (_ui.value.me?.taskPlaces != true) return@launch
            (repo.api.places() as? NResult.Ok)?.value?.let { p -> _ui.update { it.copy(places = p) } }
        }
    }

    /** `prefs.places`: the place search for me, the hint „in der Nähe“, its quiet hours — `me.places` and the phone's places follow. */
    private fun putPlaces(body: com.google.gson.JsonObject) {
        viewModelScope.launch {
            val r = repo.api.putPrefs(body)
            if (r is NResult.Ok) {
                repo.setPrefs(r.value)
                _ui.update { it.copy(prefs = r.value) }
                repo.refreshMe()?.let { me -> _ui.update { it.copy(me = me.value) } }
                repo.touched("places")
            } else {
                _message.value = r.code()
            }
        }
    }

    fun setPlaceSearch(on: Boolean) = putPlaces(com.gatecontrol.android.notizen.NotizenPlacesJson.placePrefsBody(search = on))
    fun setPlaceNearby(on: Boolean) = putPlaces(com.gatecontrol.android.notizen.NotizenPlacesJson.placePrefsBody(nearby = on))

    /** The quiet hours of the hint „in der Nähe“; null: none. The same time twice would be none — refused here. */
    fun setPlaceQuiet(q: com.gatecontrol.android.notizen.NQuiet?) {
        if (q != null && q.from == q.to) return
        putPlaces(com.gatecontrol.android.notizen.NotizenPlacesJson.placePrefsBody(quiet = q, noQuiet = q == null))
    }

    private fun placeChange(call: suspend () -> NResult<*>, ok: String? = null) {
        viewModelScope.launch {
            val r = call()
            _message.value = when {
                r is NResult.Ok -> ok
                r is NResult.Offline -> "place_offline"
                r is NResult.Failed && r.code == "too_many" -> "too_many"
                else -> r.code()
            }
            loadPlaces()
            if (r is NResult.Ok) repo.touched("places")
        }
    }

    /** „Neuen Ort merken“: a point of the map with a name. */
    fun addPlace(name: String, lat: Double, lon: Double) {
        if (name.isBlank()) return
        placeChange({ repo.api.createPlace(com.gatecontrol.android.notizen.NotizenPlacesJson.newPlaceBody(name, lat, lon, null, null, null)) }, "place_saved")
    }

    fun renamePlace(id: Long, name: String) {
        if (name.isBlank()) return
        placeChange({ repo.api.patchPlace(id, com.google.gson.JsonObject().apply { addProperty("name", name.trim().take(80)); addProperty("named", true) }) })
    }

    /** Tasks that hang on the place keep it. */
    fun deletePlace(id: Long) = placeChange({ repo.api.deletePlace(id) })

    /** „Nicht mehr merken für: Baumarkt“. */
    fun forgetPlace(key: String) = placeChange({ repo.api.placeFav(key, null) })

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _message = MutableStateFlow<NMessage?>(null)
    val message: StateFlow<NMessage?> = _message.asStateFlow()

    init { load() }

    fun consumeMessage() { _message.value = null }

    fun load() {
        viewModelScope.launch {
            val me = repo.refreshMe()
            val s = repo.api.sources()
            _ui.update { it.copy(me = me?.value, prefs = me?.value?.prefs ?: it.prefs, sources = s.valueOrNull() ?: it.sources, loading = false, offline = s is NResult.Offline) }
            loadPlaces()
            if (s is NResult.Failed) _message.value = s.code()
        }
    }

    private fun put(body: com.google.gson.JsonObject) {
        viewModelScope.launch {
            val r = repo.api.putPrefs(body)
            if (r is NResult.Ok) {
                _ui.update { it.copy(prefs = r.value) }
                repo.setPrefs(r.value)
                // „Erinnerungen an Aufgaben“ off or on: the phone's own alarms follow
                repo.touched("items", "tasks")
            } else {
                _message.value = r.code()
            }
        }
    }

    fun setSource(plugin: String, p: NSourcePrefs) = put(NotizenJson.prefsBody(sources = mapOf(plugin to p)))

    /** A group of a source (`<plugin>|<group id>`) to an area or hidden; null: as the source says. */
    fun setGroup(key: String, value: String?) = put(NotizenJson.prefsBody(groups = mapOf(key to value)))

    fun setEvening(time: String) = put(NotizenJson.prefsBody(evening = time))

    /** A word of the quick entry („morgens“ …) → its time. */
    fun setTime(word: String, time: String) = put(NotizenJson.prefsBody(times = mapOf(word to time)))

    /** Plugin 1.2.0: `prefs.myday.listdays` / `showDone`. */
    fun setMyDay(key: String, value: Boolean) = put(NotizenJson.prefsBody(myday = mapOf(key to value)))

    /** Plugin 1.2.0: `prefs.notify.reminders` / `shared` / `listday`. */
    fun setNotify(key: String, value: Boolean) = put(NotizenJson.prefsBody(notify = mapOf(key to value)))

    /** Plugin 1.3.0: when the morning hint „Gestern offen geblieben“ comes. */
    fun setYesterdayAt(time: String) = put(NotizenJson.prefsBody(yesterdayAt = time))

    /** Plugin 1.3.0 (`prefs.calendar.tasks`): my tasks with a time in my calendar in Mail — `me.calendar.mine` follows. */
    fun setCalendarTasks(value: Boolean) {
        viewModelScope.launch {
            val r = repo.api.putPrefs(NotizenJson.prefsBody(calendarTasks = value))
            if (r is NResult.Ok) {
                repo.setPrefs(r.value)
                _ui.update { it.copy(prefs = r.value) }
                repo.refreshMe()?.let { me -> _ui.update { it.copy(me = me.value) } }
                repo.touched("tasks")
            } else {
                _message.value = r.code()
            }
        }
    }

    /** Plugin 1.2.0: „Beim Öffnen“ — `last`, `private`, `work`. */
    fun setStartArea(value: String) = put(NotizenJson.prefsBody(startArea = value))

    fun sync(plugin: String) {
        viewModelScope.launch {
            val r = repo.api.syncSource(plugin)
            _message.value = r.code() ?: if (r.valueOrNull() == true) "sync_scheduled" else "sync_recent"
        }
    }
}

/** „Umziehen“ (mockup Import): Microsoft To Do through Mail's account, Synology DS Note from its .nsx export. */
@HiltViewModel
class NotizenImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: NotizenRepository,
    private val vault: NotizenVaultSession,
) : ViewModel() {

    data class Ms(
        val loading: Boolean = true,
        val available: Boolean = false,
        val reason: String? = null,
        val accounts: List<NMsAccount> = emptyList(),
        val account: Long? = null,
        val consent: NMsConsent? = null,
        val preview: NMsPreview? = null,
        val lists: List<NMsList> = emptyList(),
        val doneDays: Int = 90,
        val twoWay: Boolean = false,
        val running: Boolean = false,
        val stats: Map<String, Int> = emptyMap(),
        val state: String? = null,
        val links: List<NMsLink> = emptyList(),
    )

    data class Ds(
        val fileName: String? = null,
        val reading: Boolean = false,
        val summary: Nsx.Summary? = null,
        val error: String? = null,
        val running: Boolean = false,
        val done: Int = 0,
        val result: NsxImport.Stats? = null,
        /** The recovery keys of the Tresor notebooks made for protected notes (shown before the import goes on). */
        val recovery: List<Pair<String, String>> = emptyList(),
    )

    data class Ui(val me: NMe? = null, val ms: Ms = Ms(), val ds: Ds = Ds())

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _message = MutableStateFlow<NMessage?>(null)
    val message: StateFlow<NMessage?> = _message.asStateFlow()

    private var pollJob: Job? = null
    private var source: UriNsxSource? = null
    private var archive: Nsx.Archive? = null
    private var pendingImport: (suspend () -> Unit)? = null

    init {
        viewModelScope.launch {
            val me = repo.meOrCached()
            _ui.update { it.copy(me = me) }
            if (me?.imports?.mstodo == true) loadMs()
        }
    }

    fun consumeMessage() { _message.value = null }

    private fun ms(f: (Ms) -> Ms) = _ui.update { it.copy(ms = f(it.ms)) }
    private fun ds(f: (Ds) -> Ds) = _ui.update { it.copy(ds = f(it.ds)) }

    // ── Microsoft To Do ─────────────────────────────────────

    private suspend fun loadMs() {
        val r = repo.api.msAccounts()
        val a = r.valueOrNull()
        ms { it.copy(loading = false, available = a?.available == true, reason = a?.reason ?: r.code(), accounts = a?.accounts.orEmpty(), account = it.account ?: a?.accounts?.firstOrNull()?.id) }
        repo.api.msLinks().valueOrNull()?.let { l -> ms { it.copy(links = l) } }
        if (_ui.value.ms.account != null) preview()
    }

    fun selectAccount(id: Long) {
        ms { it.copy(account = id, preview = null, lists = emptyList(), consent = null) }
        viewModelScope.launch { preview() }
    }

    private suspend fun preview() {
        val id = _ui.value.ms.account ?: return
        val acc = _ui.value.ms.accounts.firstOrNull { it.id == id } ?: return
        if (!acc.tasks) return
        ms { it.copy(loading = true) }
        when (val r = repo.api.msPreview(id)) {
            is NResult.Ok -> ms { it.copy(loading = false, preview = r.value, lists = r.value.lists) }
            is NResult.Failed -> {
                ms { m -> m.copy(loading = false, accounts = if (r.code == "consent_required") m.accounts.map { if (it.id == id) it.copy(tasks = false) else it } else m.accounts) }
                if (r.code != "consent_required") _message.value = r.code()
            }
            else -> { ms { it.copy(loading = false) }; _message.value = "offline" }
        }
    }

    /** „Bei Microsoft zustimmen“: the device code to enter at Microsoft, asked again until it is done. */
    fun consent() {
        val id = _ui.value.ms.account ?: return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val r = repo.api.msConsent(id)
            val c = r.valueOrNull() ?: run { _message.value = r.code(); return@launch }
            ms { it.copy(consent = c) }
            val until = System.currentTimeMillis() + c.expiresIn * 1000L
            while (System.currentTimeMillis() < until) {
                delay(maxOf(5, c.interval) * 1000L)
                val p = repo.api.msConsent(id, c.deviceId).valueOrNull() ?: continue
                when (p.status) {
                    "pending" -> continue
                    "done" -> {
                        ms { m -> m.copy(consent = null, accounts = m.accounts.map { if (it.id == id) it.copy(tasks = true) else it }) }
                        _message.value = "consented"
                        preview()
                        return@launch
                    }
                    else -> {
                        ms { it.copy(consent = null) }
                        _message.value = "consent_failed"
                        return@launch
                    }
                }
            }
            ms { it.copy(consent = null) }
            _message.value = "consent_failed"
        }
    }

    fun cancelConsent() {
        pollJob?.cancel()
        ms { it.copy(consent = null) }
    }

    fun setList(l: NMsList) = ms { m -> m.copy(lists = m.lists.map { if (it.id == l.id) l else it }) }
    fun setDoneDays(d: Int) = ms { it.copy(doneDays = d) }
    fun setTwoWay(v: Boolean) = ms { it.copy(twoWay = v) }

    fun startMs() {
        val m = _ui.value.ms
        val id = m.account ?: return
        viewModelScope.launch {
            ms { it.copy(running = true, state = "running") }
            val r = repo.api.msStart(NotizenJson.msStartBody(id, m.lists, m.doneDays, m.twoWay))
            val job = r.valueOrNull() ?: run { ms { it.copy(running = false, state = null) }; _message.value = r.code(); return@launch }
            while (true) {
                val j = repo.api.job(job.id).valueOrNull()
                if (j != null) {
                    ms { it.copy(stats = j.stats, state = j.state) }
                    if (j.state != "running") break
                }
                delay(4_000)
            }
            ms { it.copy(running = false) }
            _message.value = if (_ui.value.ms.state == "done") "import_done" else "import_failed"
            repo.touched("lists", "tasks")
            repo.api.msLinks().valueOrNull()?.let { l -> ms { it.copy(links = l) } }
        }
    }

    fun unlink(l: NMsLink) {
        viewModelScope.launch {
            _message.value = repo.api.msUnlink(l.id).code() ?: "unlinked"
            repo.api.msLinks().valueOrNull()?.let { x -> ms { it.copy(links = x) } }
        }
    }

    // ── DS Note ─────────────────────────────────────────────

    /** The export the person picked: read here, on the phone (only the directory and the JSON entries). */
    fun pick(uri: Uri) {
        viewModelScope.launch {
            ds { Ds(reading = true) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "export.nsx"
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    source?.close()
                    val src = UriNsxSource(context.contentResolver, uri)
                    source = src
                    val arc = Nsx.open(src)
                    archive = arc
                    Nsx.summary(arc)
                }
            }
            r.onSuccess { s -> ds { it.copy(reading = false, fileName = name, summary = s) } }
                .onFailure { e -> ds { it.copy(reading = false, fileName = name, error = (e as? Nsx.NsxException)?.code ?: "invalid") } }
        }
    }

    /**
     * „… Notizen übernehmen“. With protected notes and a password that opens them, a Tresor notebook per notebook is
     * made first and its recovery key shown ([Ds.recovery]); the import goes on with [confirmRecovery].
     */
    fun startDs(password: String) {
        val arc = archive ?: return
        val sum = _ui.value.ds.summary ?: return
        val me = _ui.value.me ?: return
        val area = repo.currentArea()
        val importer = NsxImport(repo.api, vault, arc, sum, area, me.attachMB)
        viewModelScope.launch {
            ds { it.copy(running = true, done = 0, result = null) }
            val pw = password.takeIf { it.isNotEmpty() && sum.protectedNotes > 0 }
            val opens = pw != null && withContext(Dispatchers.IO) { importer.opens(pw) }
            if (pw != null && !opens) _message.value = "dsnote_password"
            val drafts = if (opens && me.vaults) importer.protectedBooks().map { it to vault.draft() } else emptyList()
            val run: suspend () -> Unit = {
                // the Tresor notebooks are made only now, after the person saw their recovery keys
                val tresors = HashMap<String, NsxImport.TresorFor>()
                for ((b, draft) in drafts) {
                    val made = vault.create(draft, b.title + " (DS Note)", area)
                    if (made is NResult.Ok) tresors[b.id] = NsxImport.TresorFor(made.value.notebook?.id ?: made.value.vault.notebookId, made.value.vault.id)
                }
                val r = withContext(Dispatchers.IO) {
                    importer.run(_ui.value.ds.fileName ?: "export.nsx", pw.takeIf { opens }, tresors) { st -> ds { it.copy(done = st.done) } }
                }
                ds { it.copy(running = false, result = r.valueOrNull(), recovery = emptyList()) }
                _message.value = r.code() ?: "dsnote_done"
                repo.touched("notebooks", "notes")
            }
            if (drafts.isNotEmpty()) {
                // the recovery keys are shown first (README: before anything is stored); the import goes on when the person confirms
                pendingImport = run
                ds { it.copy(recovery = drafts.map { (b, d) -> b.title to d.recoveryText }) }
            } else {
                run()
            }
        }
    }

    fun confirmRecovery() {
        val run = pendingImport ?: return
        pendingImport = null
        viewModelScope.launch { run() }
    }

    override fun onCleared() {
        source?.close()
    }
}
