package com.gatecontrol.android.media.server

import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.jellyfin.JellyfinJson.bool
import com.gatecontrol.android.media.jellyfin.JellyfinJson.obj
import com.gatecontrol.android.media.jellyfin.JellyfinJson.objects
import com.gatecontrol.android.media.jellyfin.JellyfinJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What the server's plugin „Medien“ says for this device, kept (encrypted) beside the sources. */
data class MediaServerState(
    /** An answer of `v1/sources` was had at least once. */
    val known: Boolean = false,
    /** `GET /api/v1/client/plugins` lists the plugin with a tab: the right „Medien“, a source, its own licence. */
    val tab: Boolean = false,
    /** Plugin off, uninstalled or without licence (404 `plugin_unavailable`, 403 `license_required`) since [unavailableSince]. */
    val unavailable: Boolean = false,
    val unavailableSince: Long = 0,
    /** The person has the right „Medien“. */
    val media: Boolean = true,
    val rev: String? = null,
    /** The last answer of `v1/sources` (ms); 0: none yet. */
    val lastContact: Long = 0,
    val maxOfflineDays: Int = MediaServerList.DEFAULT_MAX_OFFLINE_DAYS,
    val offlineHintDays: Int = MediaServerList.DEFAULT_OFFLINE_HINT_DAYS,
    val network: MediaNetworkRule = MediaNetworkRule(),
    /** The sources set up on this device before, taken over or to be removed („Medien kommen jetzt vom Server“); empty: seen. */
    val migration: List<MediaMigration> = emptyList(),
    /** Why the automatic sign-in at a Jellyfin source did not work (by the source's id on the device). */
    val pairProblems: Map<String, String> = emptyMap(),
    /** The server's version of each share's password (by the plugin's id). */
    val secretRevs: Map<String, Int> = emptyMap(),
    val refreshing: Boolean = false,
    val pairing: Set<String> = emptySet(),
    /** `cast.enabled` of the list: „Auf TV/PC abspielen“ is allowed. */
    val cast: Boolean = false,
) {
    /** Medien shows (tab, rail, side bar): only while the plugin gives a tab and answers. */
    val visible: Boolean get() = tab && !unavailable && media

    /** „Auf TV/PC abspielen“ may be offered (and a cast taken): the plugin answers and allows it. */
    val castAllowed: Boolean get() = known && !unavailable && media && cast

    /** Long without contact to the server: „Lange keinen Kontakt zum Server“ (question 36 a). */
    fun offlineHint(now: Long): Boolean = lastContact > 0 && now - lastContact > offlineHintDays * DAY_MS

    companion object {
        const val DAY_MS = 24 * 60 * 60_000L

        fun encode(s: MediaServerState): String = JsonObject().apply {
            addProperty("known", s.known)
            addProperty("tab", s.tab)
            addProperty("unavailable", s.unavailable)
            addProperty("unavailableSince", s.unavailableSince)
            addProperty("media", s.media)
            addProperty("cast", s.cast)
            s.rev?.let { addProperty("rev", it) }
            addProperty("lastContact", s.lastContact)
            addProperty("maxOfflineDays", s.maxOfflineDays)
            addProperty("offlineHintDays", s.offlineHintDays)
            add(
                "probes",
                JsonArray().apply {
                    s.network.probes.forEach { p ->
                        add(
                            JsonObject().apply {
                                when (p) {
                                    is MediaHomeProbe.Jellyfin -> { addProperty("kind", "jellyfin"); addProperty("url", p.url); addProperty("serverId", p.serverId) }
                                    is MediaHomeProbe.Tls -> { addProperty("kind", "tls"); addProperty("url", p.url); addProperty("certSha256", p.certSha256) }
                                }
                            },
                        )
                    }
                },
            )
            add(
                "migration",
                JsonArray().apply {
                    s.migration.forEach { m ->
                        add(
                            JsonObject().apply {
                                addProperty("id", m.id)
                                addProperty("kind", m.kind.wire)
                                addProperty("label", m.label)
                                m.takenAs?.let { addProperty("takenAs", it) }
                            },
                        )
                    }
                },
            )
            add("secretRevs", JsonObject().apply { s.secretRevs.forEach { (k, v) -> addProperty(k, v) } })
        }.toString()

        fun decode(text: String?): MediaServerState {
            val o = runCatching { JsonParser.parseString(text ?: return MediaServerState()).asJsonObject }.getOrNull() ?: return MediaServerState()
            fun long(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L
            fun int(k: String, d: Int) = o.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: d
            val probes = JsonObject().apply { add("home", JsonObject().apply { add("probes", o.get("probes")?.takeIf { it.isJsonArray } ?: JsonArray()) }) }
            return MediaServerState(
                known = o.bool("known") == true,
                tab = o.bool("tab") == true,
                unavailable = o.bool("unavailable") == true,
                unavailableSince = long("unavailableSince"),
                media = o.bool("media") != false,
                cast = o.bool("cast") == true,
                // kept before `cast` was: the whole list once more (an unchanged rev would never bring `cast.enabled`)
                rev = o.str("rev")?.takeIf { o.has("cast") },
                lastContact = long("lastContact"),
                maxOfflineDays = int("maxOfflineDays", MediaServerList.DEFAULT_MAX_OFFLINE_DAYS),
                offlineHintDays = int("offlineHintDays", MediaServerList.DEFAULT_OFFLINE_HINT_DAYS),
                network = MediaServerJson.network(probes),
                migration = o.objects("migration").mapNotNull { m ->
                    MediaMigration(
                        m.str("id") ?: return@mapNotNull null,
                        MediaSourceKind.fromWire(m.str("kind")) ?: return@mapNotNull null,
                        m.str("label") ?: return@mapNotNull null,
                        m.str("takenAs"),
                    )
                },
                secretRevs = o.obj("secretRevs")?.entrySet()?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { k to it.asInt } }?.toMap().orEmpty(),
            )
        }
    }
}

/** How „Neu anmelden“ of a page ended ([MediaServerSources.signIn]). */
sealed interface MediaSignIn {
    /** Signed in (or nothing to sign in any more): the page loads anew. */
    data object Done : MediaSignIn

    /** A source set up on this device by hand: the way on is Medien › Quellen (its dialog). */
    data object Manual : MediaSignIn

    /** Why not: a code as in [MediaServerState.pairProblems] (the plugin's, [MediaPairEnd]'s or [MediaSourcesText]'s). */
    data class Problem(val code: String) : MediaSignIn
}

/**
 * The media sources from the server (plugin „Medien“, App-API v1): the list (`v1/sources`, with `?since=<rev>`) merged
 * into [com.gatecontrol.android.media.MediaSourceStore] and kept there encrypted (the cache: without the server the last
 * list counts, at home media play on), the shares' passwords (`secret`, when `secretRev` is newer), the automatic
 * sign-in at Jellyfin ([MediaAutoPair]), the revocation (silent push `media.revoke`, `no_access`), the event
 * `media.changed`, the takeover of sources set up on this device before (question 33 a), the offline limits (question
 * 36 a: a hint after `offlineHintDays`, the NAS passwords gone after `maxOfflineDays`) and the plugin switched off
 * (question 37 a: hidden at once, deleted after 24 h). Shared by the phone app and GateControl TV.
 */
@Singleton
class MediaServerSources internal constructor(
    private val hub: MediaHub,
    private val api: MediaServerApi,
    private val now: () -> Long,
    private val pair: MediaAutoPair,
    private val scope: CoroutineScope,
    /** „Auf TV/PC abspielen“: where the deliveries `media.cast` go (event and silent push). */
    private val cast: com.gatecontrol.android.media.cast.MediaCastChannel? = null,
) {
    @Inject
    constructor(hub: MediaHub, api: MediaServerApi, cast: com.gatecontrol.android.media.cast.MediaCastChannel) :
        this(hub, api, System::currentTimeMillis, MediaAutoPair(hub, api), CoroutineScope(SupervisorJob() + Dispatchers.IO), cast)

    private val store get() = hub.store

    private val _state = MutableStateFlow(MediaServerState.decode(store.serverState()).also { hub.setNetworkRule(it.network) })
    val state: StateFlow<MediaServerState> = _state.asStateFlow()

    private val mutex = Mutex()

    /** One automatic sign-in at a time (the list's and „Neu anmelden“'s would race for the same source). */
    private val pairLock = Mutex()

    @Volatile
    private var lastAttempt = 0L

    /** When a source's automatic sign-in was tried last (by its id), so a failing one does not run on every opening. */
    private val pairTried = ConcurrentHashMap<String, Long>()

    /** The way to each source last told to the plugin (`report`, only on a change). */
    private val reported = ConcurrentHashMap<String, String>()

    init {
        hub.reachListener = { source, route, ok -> onReach(source, route, ok) }
    }

    private fun save(next: MediaServerState) {
        _state.value = next
        runCatching { store.setServerState(MediaServerState.encode(next)) }
    }

    private fun change(f: (MediaServerState) -> MediaServerState) = synchronized(this) { save(f(_state.value)) }

    /** App start, foreground: at most every [EVERY_MS] (the plugin's „sonst höchstens alle 15 Minuten“). */
    fun refreshIfStale() {
        if (now() - lastAttempt < EVERY_MS) return
        scope.launch { refresh() }
    }

    /** Opening Medien: the list again unless it was asked a moment ago. */
    fun refreshSoon() {
        if (now() - lastAttempt < OPEN_MS) return
        scope.launch { refresh() }
    }

    fun refreshLater() {
        scope.launch { refresh(force = true) }
    }

    /**
     * Asks the server for the list and merges it; then the passwords and the sign-in at Jellyfin. Without an answer the
     * cached list stays. Never throws except for cancellation.
     */
    suspend fun refresh(force: Boolean = false) = refresh(force, pair = true)

    /** [refresh]; with [pair] false the sign-in at Jellyfin is left to the caller ([signIn]). */
    private suspend fun refresh(force: Boolean, pair: Boolean) {
        var answered = false
        mutex.withLock {
            if (!force && now() - lastAttempt < MIN_MS) return@withLock
            lastAttempt = now()
            change { it.copy(refreshing = true) }
            try {
                api.tab()?.let { tab -> change { it.copy(tab = tab) } }
                val since = _state.value.rev?.takeIf { _state.value.known && store.sources.value.any { it.managed } }
                when (val r = api.sources(since)) {
                    is MediaServerResult.Ok -> {
                        apply(r.value)
                        answered = true
                    }
                    is MediaServerResult.Refused -> refused(r.error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Medien: list not loaded (%s)", e.javaClass.simpleName)
            } finally {
                change { it.copy(refreshing = false) }
                enforceOffline()
            }
        }
        // the passwords only with an answer of the list (not offline, not unavailable)
        if (answered && _state.value.visible) {
            syncSecrets()
            if (pair) pairAll()
        }
    }

    private fun apply(list: MediaServerList) {
        if (list.unchanged) {
            change { it.copy(known = true, lastContact = now(), unavailable = false, unavailableSince = 0) }
            return
        }
        val merged = MediaServerMerge.merge(store.sources.value, list)
        store.replaceAll(merged.list, merged.gone)
        merged.gone.forEach { hub.forget(it) }
        merged.signOut.forEach { old -> scope.launch { pair.signOutOld(old) } }
        hub.setNetworkRule(list.network)
        val revs = list.sources.filter { it.kind == MediaServerSource.Kind.SHARE }.associate { it.id to it.secretRev }
        change { s ->
            // the sheet keeps what it said first (a source taken over is the server's afterwards)
            val migration = if (merged.migration.isEmpty() && s.migration.isEmpty()) {
                emptyList()
            } else {
                (s.migration + merged.migration.filter { m -> s.migration.none { it.id == m.id } }).takeIf { list.media }.orEmpty()
            }
            s.copy(
                known = true,
                unavailable = false,
                unavailableSince = 0,
                media = list.media,
                cast = list.cast && list.media,
                rev = list.rev,
                lastContact = now(),
                maxOfflineDays = list.maxOfflineDays,
                offlineHintDays = list.offlineHintDays,
                network = list.network,
                migration = migration,
                secretRevs = revs,
                pairProblems = s.pairProblems.filterKeys { id -> merged.list.any { it.id == id } },
            )
        }
    }

    private fun refused(e: MediaServerError) {
        if (!e.unavailable) return
        val since = _state.value.unavailableSince.takeIf { it > 0 } ?: now()
        change { it.copy(unavailable = true, unavailableSince = since, known = true) }
        // switched off for a day: the accesses, passwords and downloads go (question 37 a)
        if (now() - since >= UNAVAILABLE_KEEP_MS && store.sources.value.isNotEmpty()) {
            Timber.i("Medien: plugin unavailable for a day, sources removed")
            removeAll(store.sources.value)
            change { it.copy(rev = null, migration = emptyList()) }
        }
    }

    private fun removeAll(list: List<MediaSource>) {
        if (list.isEmpty()) return
        val ids = list.map { it.id }.toSet()
        store.replaceAll(store.sources.value.filterNot { it.id in ids }.map { if (it.pairWith in ids) it.copy(pairWith = null) else it }, ids)
        ids.forEach { hub.forget(it) }
    }

    /** Question 36 a: after `maxOfflineDays` without an answer the NAS passwords on the device go. */
    private fun enforceOffline() {
        val s = _state.value
        if (s.lastContact <= 0) return
        if (now() - s.lastContact > s.maxOfflineDays * MediaServerState.DAY_MS && store.sources.value.any { it.managed && it.kind.share && it.password != null }) {
            Timber.i("Medien: long without contact to the server, the NAS access data on the device is removed")
            store.dropPasswords()
            store.sources.value.filter { it.kind.share }.forEach { hub.forget(it.id) }
        }
    }

    /** The shares' passwords whose version on the server is newer than the one kept (or none is kept). */
    private suspend fun syncSecrets() {
        val revs = _state.value.secretRevs
        val groups = store.sources.value.filter { it.managed && it.kind.share && it.remoteId != null }.groupBy { it.remoteId!! }
        for ((remoteId, halves) in groups) {
            val rev = revs[remoteId] ?: 0
            if (halves.all { it.password != null && it.secretRev >= rev }) continue
            when (val r = api.secret(remoteId)) {
                is MediaServerResult.Ok -> {
                    halves.forEach { store.setPassword(it.id, r.value.user, r.value.password, r.value.rev.takeIf { v -> v > 0 } ?: rev) }
                    halves.forEach { hub.forget(it.id) }
                    checkSmb(remoteId)
                }
                is MediaServerResult.Refused -> if (r.error.code == "no_access") removeAll(halves)
            }
        }
    }

    /** After the first SMB sign-in with the server's password (at home): the plugin shows „SMB geprüft“. */
    private suspend fun checkSmb(remoteId: String) {
        val smb = store.sources.value.firstOrNull { it.remoteId == remoteId && it.kind == MediaSourceKind.SMB && it.password != null } ?: return
        if (hub.route(smb) != MediaRoute.HOME) return
        val r = hub.tryShare(smb)
        if (r == MediaResult.Offline) return
        val problem = when (r) {
            is MediaResult.Ok -> null
            MediaResult.Unauthorized -> "login"
            MediaResult.NotFound -> "not_found"
            else -> "failed"
        }
        runCatching { api.checked(remoteId, problem == null, problem) }
    }

    /** The Jellyfin sources without this device's access: signed in automatically, one at a time. */
    private suspend fun pairAll(force: Boolean = false) {
        val todo = store.sources.value.filter { it.managed && it.kind == MediaSourceKind.JELLYFIN && it.token == null && it.linkedUserId != null && it.deviceId != null }
        for (s in todo) {
            val last = pairTried[s.id] ?: 0
            if (!force && now() - last < PAIR_EVERY_MS) continue
            pairOne(s.id)
        }
    }

    /** The automatic sign-in at one source (as it is in the store now); its end is kept in [MediaServerState.pairProblems]. */
    private suspend fun pairOne(id: String): MediaPairEnd = pairLock.withLock {
        val s = store.sources.value.firstOrNull { it.id == id } ?: return@withLock MediaPairEnd.Problem(MediaPairEnd.FAILED)
        // signed in meanwhile (the list's sign-in ran first)
        if (s.token != null) return@withLock MediaPairEnd.Paired
        if (hub.route(s) == MediaRoute.BLOCKED) {
            change { it.copy(pairProblems = it.pairProblems + (s.id to MediaPairEnd.BLOCKED)) }
            return@withLock MediaPairEnd.Problem(MediaPairEnd.BLOCKED)
        }
        pairTried[s.id] = now()
        change { it.copy(pairing = it.pairing + s.id) }
        val end = try {
            pair.pair(s)
        } finally {
            change { it.copy(pairing = it.pairing - s.id) }
        }
        change {
            when (end) {
                MediaPairEnd.Paired -> it.copy(pairProblems = it.pairProblems - s.id)
                is MediaPairEnd.Problem -> it.copy(pairProblems = it.pairProblems + (s.id to end.code))
            }
        }
        if (end is MediaPairEnd.Problem && end.code == "no_access") removeAll(listOf(s))
        if (end is MediaPairEnd.Problem && end.code == MediaPairEnd.OFFLINE) onReach(s, hub.route(s), false)
        end
    }

    /**
     * Medien was opened or Jellyfin refused the access (401): signed in again right away (also a source that failed a
     * moment ago). The VPN, when needed away, was started by the page ([MediaHub.prepare]).
     */
    fun repair() {
        scope.launch { pairAll(force = true) }
    }

    /**
     * „Neu anmelden“ of a page for the source [sourceId], waited for: a source set up by hand → [MediaSignIn.Manual]
     * (its sign-in is on Medien › Quellen); one of the server → the list again first when it may be old (revoked, a
     * new device or mapping in the plugin, no answer for a while), then the automatic sign-in at once. The VPN, when
     * needed away, was started by the page ([MediaHub.prepare]). Never throws except for cancellation.
     */
    suspend fun signIn(sourceId: String): MediaSignIn {
        var s = store.source(sourceId) ?: return MediaSignIn.Done
        if (!s.managed) return if (s.token != null || s.kind != MediaSourceKind.JELLYFIN) MediaSignIn.Done else MediaSignIn.Manual
        if (s.kind != MediaSourceKind.JELLYFIN) return MediaSignIn.Done
        if (listOld(s)) {
            refresh(force = true, pair = false)
            if (_state.value.unavailable) return MediaSignIn.Problem(MediaSourcesText.UNAVAILABLE)
            // gone from the list (revoked, removed): the page shows what is left
            s = store.source(sourceId) ?: return MediaSignIn.Done
        }
        if (s.token != null) return MediaSignIn.Done
        val code = when {
            s.problem != null -> s.problem
            s.linkedUserId == null -> "no_mapping"
            s.deviceId == null -> MediaPairEnd.FAILED
            else -> null
        }
        if (code != null) {
            change { it.copy(pairProblems = it.pairProblems + (s.id to code)) }
            return MediaSignIn.Problem(code)
        }
        return when (val end = pairOne(s.id)) {
            MediaPairEnd.Paired -> MediaSignIn.Done
            is MediaPairEnd.Problem -> MediaSignIn.Problem(end.code)
        }
    }

    /** The list may be old for [s]: revoked (no rev), what the sign-in needs is missing, or no answer for a while. */
    private fun listOld(s: MediaSource): Boolean {
        val st = _state.value
        return !st.known || st.rev == null || s.deviceId == null || s.linkedUserId == null || s.problem != null || now() - st.lastContact > OPEN_MS
    }

    /**
     * A push of the topic `plugin:gatecontrol-medien:media_signal`: consumed here, never shown (true); `media.revoke`
     * handled, `media.cast` handed to the cast channel. Anything of another topic: false.
     */
    fun consume(topic: String, dataJson: String?): Boolean {
        if (topic != MEDIA_SIGNAL_TOPIC) return false
        val data = runCatching { dataJson?.takeIf { it.length < 64_000 }?.let { JsonParser.parseString(it) } }.getOrNull()
        if (isCast(data)) {
            if (_state.value.castAllowed) cast?.receive(data, push = true)
            return true
        }
        onSignal(data)
        return true
    }

    private fun isCast(data: JsonElement?): Boolean =
        data?.takeIf { it.isJsonObject }?.asJsonObject?.str("kind") == com.gatecontrol.android.media.cast.CastEnvelopes.KIND

    /** The silent data push of the plugin (topic `media_signal`): `media.revoke`; true when it was one of ours. */
    fun onSignal(data: JsonElement?): Boolean {
        val r = MediaServerJson.revoke(data) ?: return false
        revoke(r)
        return true
    }

    /**
     * Revoked (device removed, right or mapping changed, the source gone): the Jellyfin access, the password and the
     * downloads of these sources (null: all) go; then the list again (signed in anew if still allowed).
     */
    fun revoke(r: MediaRevoke) {
        val ids = r.sourceIds
        val hit = store.sources.value.filter { it.managed && (ids == null || it.remoteId in ids) }
        Timber.i("Medien: %d source(s) revoked (%s)", hit.size, r.reason ?: "?")
        removeAll(hit)
        hit.forEach { pairTried.remove(it.id) }
        change { it.copy(rev = null) }
        refreshLater()
    }

    /** `event: plugin` of the push stream. */
    fun onEvent(type: String, data: JsonElement?) {
        when (type) {
            "media.changed" -> refreshLater()
            "media.revoke" -> onSignal(data)
            com.gatecontrol.android.media.cast.CastEnvelopes.KIND -> if (_state.value.castAllowed) cast?.receive(data, push = false)
        }
    }

    /** „Verstanden“ on the sheet: the device's own sources the server does not have go, their Jellyfin access is signed out. */
    fun acknowledgeMigration() {
        val legacy = store.sources.value.filter { !it.managed }
        legacy.filter { it.kind == MediaSourceKind.JELLYFIN && it.token != null }.forEach { old -> scope.launch { pair.signOutOld(old) } }
        removeAll(legacy)
        change { it.copy(migration = emptyList()) }
    }

    /** The app is signed out or the TV unpaired: this device is signed out of Jellyfin by the plugin (best effort). */
    suspend fun signOutDevice() {
        if (store.sources.value.none { it.managed && it.kind == MediaSourceKind.JELLYFIN }) return
        runCatching { api.deleteDevice(null) }
    }

    /** Forget everything of the server (unpaired; the store is cleared by [MediaHub.clear]). */
    fun clear() {
        cast?.clear()
        pairTried.clear()
        reported.clear()
        lastAttempt = 0
        _state.value = MediaServerState()
    }

    private fun onReach(source: MediaSource, route: MediaRoute, ok: Boolean) {
        val remoteId = source.remoteId?.takeIf { source.managed } ?: return
        if (route == MediaRoute.BLOCKED) return
        val way = if (route == MediaRoute.HOME) "home" else "away"
        val problem = when {
            ok -> null
            route == MediaRoute.VPN -> "tunnel_unreachable"
            else -> "unreachable"
        }
        val key = "$way:${problem ?: "ok"}"
        if (reported.put(remoteId, key) == key) return
        scope.launch { runCatching { api.report(remoteId, way, ok, problem) } }
    }

    companion object {
        const val EVERY_MS = 15 * 60_000L
        const val OPEN_MS = 30_000L
        private const val MIN_MS = 5_000L
        const val PAIR_EVERY_MS = 5 * 60_000L
        const val UNAVAILABLE_KEEP_MS = 24 * 60 * 60_000L
    }
}
