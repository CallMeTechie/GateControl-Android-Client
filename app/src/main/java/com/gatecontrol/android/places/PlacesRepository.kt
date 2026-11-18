package com.gatecontrol.android.places

import com.gatecontrol.android.data.EncryptedStorage
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.profile.ProfileRepository
import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Does the server know „Meine Orte“? */
enum class PlacesSupport {
    /** Not asked yet and nothing cached. */
    UNKNOWN,
    SUPPORTED,
    /** Server before 1.299 (404): the page and the choice from the profile are hidden, the forms work as before. */
    UNSUPPORTED,
    /** A token without a user (403 NO_USER). */
    NO_USER,
}

data class PlacesState(
    val list: PlacesList? = null,
    val support: PlacesSupport = PlacesSupport.UNKNOWN,
    /** The list is the copy on the phone: the last answer could not be had. */
    val offline: Boolean = false,
) {
    val places: List<MyPlace> get() = list?.places.orEmpty()
    val available: Boolean get() = support == PlacesSupport.SUPPORTED && list != null
}

/** Result of a change. */
sealed interface PlaceEdit {
    data class Done(val place: MyPlace?) : PlaceEdit

    /** Refused: the server's [code] (`INVALID`, `DUPLICATE`, `LIMIT`, `IN_USE`, `NOT_FOUND`, `RATE_LIMITED`, [PlacesRepository.CODE_OFFLINE] …). */
    data class Refused(val code: String, val field: String? = null, val message: String? = null, val usedBy: List<PlaceUse> = emptyList()) : PlaceEdit
}

/** Result of the address search. */
sealed interface PlaceSearch {
    data class Hits(val hits: List<AddressHit>, val attribution: String? = null, val url: String? = null) : PlaceSearch

    /** `SEARCH_OFF` (the phone's geocoder takes over), `RATE_LIMITED` ([retryAfterS]), `SEARCH_UNAVAILABLE`, offline … */
    data class Refused(val code: String, val retryAfterS: Int? = null) : PlaceSearch
}

/**
 * „Meine Orte“ of the profile (GateControl 1.299): loaded on start, in the foreground, on the push event `places` and on
 * a reconnect of the stream; each change answers with the place, which replaces ours at once. The copy lives in the
 * encrypted app storage (bound to the login), so the choice of a place works without a network.
 */
@Singleton
class PlacesRepository @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val secureStorage: EncryptedStorage,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(PlacesState())
    val state: StateFlow<PlacesState> = _state.asStateFlow()

    @Volatile private var lastRefreshMs = 0L
    @Volatile private var started = false

    /** App start: the copy, then the server; from now on on every push event and after a change of login. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            setupRepository.revision.collect {
                lastRefreshMs = 0L
                mutex.withLock { loadCache() }
                refreshIfStale()
            }
        }
        scope.launch {
            PlacesSignals.changes.collect { c ->
                if (c.reconnect) {
                    // a reconnect: load again unless it was done a moment ago
                    if (System.currentTimeMillis() - lastRefreshMs >= RECONNECT_INTERVAL_MS) refresh()
                } else {
                    refresh()
                }
            }
        }
    }

    private fun owner(): String = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    private suspend fun loadCache() {
        val cached = withContext(Dispatchers.IO) { MyPlaces.decodeCache(runCatching { secureStorage.getString(KEY_CACHE, "") }.getOrNull(), owner()) }
        _state.value = if (cached != null) PlacesState(cached, PlacesSupport.SUPPORTED, offline = true) else PlacesState()
    }

    private suspend fun store(list: PlacesList?, support: PlacesSupport, offline: Boolean = false) {
        val before = _state.value.list
        withContext(Dispatchers.IO) {
            runCatching {
                if (list == null) secureStorage.remove(KEY_CACHE) else secureStorage.putString(KEY_CACHE, MyPlaces.encodeCache(list, owner()))
            }
        }
        _state.value = PlacesState(list, support, offline)
        if (before?.places != list?.places) PlacesSignals.notifyReloaded()
    }

    /** On start / foreground: refresh unless it was done a moment ago. */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshMs < MIN_INTERVAL_MS) return
        lastRefreshMs = System.currentTimeMillis()
        scope.launch { refresh() }
    }

    /** The list now (the cached one when it cannot be reached). */
    fun current(): PlacesList? = _state.value.list

    /** `GET profile/places`; keeps the copy when the server cannot be reached. */
    suspend fun refresh(): PlacesState = mutex.withLock {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) {
            _state.value = PlacesState()
            return@withLock _state.value
        }
        // the copy first (a refresh before start() loaded it): without a network it is what the choice shows
        if (_state.value.list == null && _state.value.support == PlacesSupport.UNKNOWN) loadCache()
        try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).getMyPlaces() }
            if (response == null) {
                _state.value = _state.value.copy(offline = true)
                return@withLock _state.value
            }
            val body = response.body()
            when {
                response.isSuccessful && body != null -> MyPlaces.list(body)?.let { store(it, PlacesSupport.SUPPORTED) }
                response.code() == 404 -> store(null, PlacesSupport.UNSUPPORTED)
                response.code() == 403 -> store(null, PlacesSupport.NO_USER)
                else -> {
                    Timber.d("Places: HTTP %d", response.code())
                    _state.value = _state.value.copy(offline = true)
                }
            }
            if (!response.isSuccessful) response.errorBody()?.close()
            lastRefreshMs = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Places unavailable (%s)", e.javaClass.simpleName)
            _state.value = _state.value.copy(offline = true)
        }
        _state.value
    }

    // ─── changes ────────────────────────────────────────────────────

    suspend fun create(d: PlaceDraft): PlaceEdit {
        val body = MyPlaces.createBody(d) ?: return PlaceEdit.Refused(CODE_INVALID, if (MyPlaces.hasPoint(d.lat, d.lon)) "name" else "lat")
        return edit { it.addMyPlace(body.toBody()) }
    }

    suspend fun update(old: MyPlace, d: PlaceDraft): PlaceEdit {
        val body = MyPlaces.patchBody(old, d) ?: return PlaceEdit.Refused(CODE_INVALID, if (MyPlaces.hasPoint(d.lat, d.lon)) "name" else "lat")
        if (body.size() == 0) return PlaceEdit.Done(old)
        return edit { it.patchMyPlace(old.id, body.toBody()) }
    }

    /** Without [force] a place that is still in use is refused (`IN_USE` with `usedBy`). */
    suspend fun delete(id: Long, force: Boolean = false): PlaceEdit {
        val r = edit(removes = id) { it.deleteMyPlace(id, if (force) 1 else null) }
        return r
    }

    /** The place was chosen in a form: its `lastUsedAt` (the order of „Zuletzt genutzt“). Best effort, no answer awaited. */
    fun markUsed(id: Long) {
        scope.launch {
            edit { it.markMyPlaceUsed(id) }
        }
    }

    private fun JsonObject.toBody() = toString().toRequestBody(JSON)

    private suspend fun edit(removes: Long? = null, call: suspend (ApiClient) -> Response<JsonObject>): PlaceEdit {
        val url = setupRepository.getServerUrl()
        if (url.isBlank()) return PlaceEdit.Refused(CODE_OFFLINE)
        return try {
            val response = withTimeoutOrNull(WRITE_TIMEOUT_MS) { call(apiClientProvider.getClient(url)) }
                ?: return PlaceEdit.Refused(CODE_OFFLINE)
            val body = response.body()
            if (!response.isSuccessful || body == null || body.get("ok")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == false) {
                return refusal(response.code(), errorJson(response) ?: body)
            }
            val place = MyPlaces.one(body)
            mutex.withLock {
                val list = _state.value.list
                when {
                    list == null -> Unit
                    removes != null -> store(MyPlaces.remove(list, removes), PlacesSupport.SUPPORTED)
                    place != null -> store(MyPlaces.upsert(list, place), PlacesSupport.SUPPORTED)
                }
            }
            if (list() == null) scope.launch { refresh() }
            PlaceEdit.Done(place)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Place change failed (%s)", e.javaClass.simpleName)
            PlaceEdit.Refused(CODE_OFFLINE)
        }
    }

    private fun list(): PlacesList? = _state.value.list

    // ─── the address search ─────────────────────────────────────────

    /** `GET places/search` — only on an explicit „Suchen“, never per key. [near]: the phone's position (rounded here). */
    suspend fun search(q: String, near: Pair<Double, Double>?): PlaceSearch {
        val query = q.trim()
        if (!PlacePicking.searchable(query)) return PlaceSearch.Refused(CODE_INVALID)
        if (_state.value.list?.search == MyPlaces.SEARCH_OFF) return PlaceSearch.Refused(CODE_SEARCH_OFF)
        val url = setupRepository.getServerUrl()
        if (url.isBlank()) return PlaceSearch.Refused(CODE_OFFLINE)
        return try {
            val response = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                apiClientProvider.getClient(url).searchPlaces(query, MyPlaces.near(near?.first, near?.second))
            } ?: return PlaceSearch.Refused(CODE_OFFLINE)
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                val err = errorJson(response)
                val r = refusal(response.code(), err)
                val retry = err?.get("retryAfter")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                    ?: response.headers()["Retry-After"]?.toIntOrNull()
                return PlaceSearch.Refused(if (response.code() == 404 && r.code == "NOT_FOUND") CODE_SEARCH_OFF else r.code, retry)
            }
            val att = body.get("attribution")?.takeIf { it.isJsonObject }?.asJsonObject
            PlaceSearch.Hits(
                MyPlaces.hits(body),
                att?.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.take(80),
                att?.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.startsWith("https://") }?.take(200),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Place search failed (%s)", e.javaClass.simpleName)
            PlaceSearch.Refused(CODE_OFFLINE)
        }
    }

    private fun errorJson(response: Response<*>): JsonObject? = runCatching {
        val raw = response.errorBody()?.string()?.take(8_000) ?: return null
        JsonParser.parseString(raw).asJsonObject
    }.getOrNull()

    companion object {
        const val CODE_OFFLINE = ProfileRepository.CODE_OFFLINE
        const val CODE_INVALID = "INVALID"
        const val CODE_SEARCH_OFF = "SEARCH_OFF"
        const val CODE_IN_USE = "IN_USE"
        const val CODE_DUPLICATE = "DUPLICATE"
        const val CODE_LIMIT = "LIMIT"

        private const val KEY_CACHE = "profile_places_v1"
        private const val TIMEOUT_MS = 8_000L
        private const val WRITE_TIMEOUT_MS = 20_000L
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val MIN_INTERVAL_MS = 3 * 60_000L
        private const val RECONNECT_INTERVAL_MS = 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Pure: the refusal of a change — the server's `code`, `field`, `error` and, for `IN_USE`, `usedBy`. */
        fun refusal(status: Int, o: JsonObject?): PlaceEdit.Refused {
            val r = ProfileRepository.refusal(status, o)
            val code = when {
                r.code == "CONFLICT" && o?.get("usedBy")?.isJsonArray == true -> CODE_IN_USE
                else -> r.code
            }
            return PlaceEdit.Refused(code, r.field, r.message, if (code == CODE_IN_USE) MyPlaces.refusalUses(o) else emptyList())
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlacesEntryPoint {
    fun placesRepository(): PlacesRepository
}
