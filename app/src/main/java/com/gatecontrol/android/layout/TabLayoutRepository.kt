package com.gatecontrol.android.layout

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.ClientLayoutRequest
import com.gatecontrol.android.support.CrashReports
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the device keeps of the tab order: the [layout] shown, the server's
 * last copy ([remote], to keep other devices' entries when saving), its
 * [access] and whether a change still has to reach the server ([pending]).
 * [owner] is the login it belongs to; another login starts from the device
 * order without the old server's copy.
 */
data class StoredTabLayout(
    val layout: TabLayout = TabLayout(),
    val remote: TabLayout? = null,
    val access: LayoutAccess = LayoutAccess(),
    val pending: Boolean = false,
    val owner: String? = null,
) {
    companion object {
        private val gson = Gson()

        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
        fun decode(raw: String?): StoredTabLayout = try {
            if (raw.isNullOrBlank()) StoredTabLayout()
            else gson.fromJson(raw, StoredTabLayout::class.java)?.let { s ->
                // Gson bypasses Kotlin null-safety and defaults: mend what an older shape lacks.
                StoredTabLayout(
                    layout = (s.layout ?: TabLayout()).sanitized(),
                    remote = s.remote?.sanitized(),
                    access = s.access?.let { a -> LayoutAccess(a.scope ?: LayoutScope.DEVICE, a.editable, a.server) } ?: LayoutAccess(),
                    pending = s.pending,
                    owner = s.owner,
                )
            } ?: StoredTabLayout()
        } catch (e: Exception) {
            StoredTabLayout()
        }

        fun encode(stored: StoredTabLayout): String = gson.toJson(stored)
    }
}

/**
 * Order of the start and Familie tabs (core 1.181+: `GET/PUT
 * /api/v1/client/layout`). With the scope `user` or `global` the order
 * comes from the account and is the same on every device; a change here is
 * sent right away (and again on the next [refresh] when that failed). With
 * the scope `device`, or a server without layouts (404), the device keeps
 * it alone, as before. A global order only an admin may change is
 * [LayoutAccess.locked]: the tabs show it, arranging is off.
 */
@Singleton
class TabLayoutRepository @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()
    private var lastRefresh = 0L

    private val stored: StateFlow<StoredTabLayout> = settingsRepository.getTabLayout()
        .map { forOwner(StoredTabLayout.decode(it)) }
        .stateIn(scope, SharingStarted.Eagerly, StoredTabLayout())

    val layout: StateFlow<TabLayout> = stored.map { it.layout }
        .stateIn(scope, SharingStarted.Eagerly, TabLayout())

    val access: StateFlow<LayoutAccess> = stored.map { it.access }
        .stateIn(scope, SharingStarted.Eagerly, LayoutAccess())

    /** Straight from storage (the flows may not have loaded yet). */
    suspend fun current(): TabLayout = read().layout

    private suspend fun read(): StoredTabLayout = forOwner(StoredTabLayout.decode(settingsRepository.getTabLayout().first()))

    private fun owner(): String = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    /** [s] for the current login: another login keeps the order shown, not the old server's copy and access. */
    private fun forOwner(s: StoredTabLayout): StoredTabLayout {
        val owner = owner()
        return if (s.owner == owner) s else StoredTabLayout(layout = s.layout, owner = owner)
    }

    private suspend fun write(transform: (StoredTabLayout) -> StoredTabLayout): StoredTabLayout {
        var next = StoredTabLayout()
        settingsRepository.updateTabLayout { raw ->
            next = transform(forOwner(StoredTabLayout.decode(raw)))
            StoredTabLayout.encode(next)
        }
        return next
    }

    /**
     * Asks the server for the account's order (at most every
     * [MIN_REFRESH_MS] unless [force]). A local change not sent yet goes
     * first; with no order on the server yet, the device's becomes it.
     */
    suspend fun refresh(force: Boolean = false): Unit = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < MIN_REFRESH_MS) return
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isBlank()) return
        lastRefresh = now
        try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(serverUrl).getClientLayout() } ?: return
            when {
                response.code() == 404 -> write { it.copy(access = LayoutAccess(server = false), pending = false) }
                !response.isSuccessful -> Timber.d("Tab layout: HTTP %d", response.code())
                else -> {
                    val body = response.body() ?: return
                    val scopeNow = LayoutScope.parse(body.scope) ?: LayoutScope.DEVICE
                    val access = LayoutAccess(scopeNow, editable = body.editable != false, server = true)
                    val remote = TabLayout.from(body.layout)
                    val local = read()
                    when {
                        !access.shared -> write { it.copy(access = access, remote = null, pending = false) }
                        access.editable && (local.pending || remote == null) && local.layout != TabLayout() ->
                            // A change made here (or the first order of the account): the device's goes up.
                            push(local.copy(access = access, remote = remote))
                        else -> write { it.copy(layout = remote ?: it.layout, remote = remote, access = access, pending = false) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Tab layout unavailable (%s)", e.javaClass.simpleName)
        }
    }

    /** The start tab's new order (StartLayout entries). */
    suspend fun saveStart(entries: List<String>) = save { it.copy(start = TabLayout.clean(entries)) }

    /** The Familie tab's new order and hidden cards (FamilieLayout ids). */
    suspend fun saveFamilie(order: List<String>, hidden: List<String>) =
        save { it.copy(familie = TabLayout.clean(order), familieHidden = TabLayout.clean(hidden)) }

    /** A locked order (global, admins only) stays as the server has it. */
    private suspend fun save(change: (TabLayout) -> TabLayout) {
        val next = mutex.withLock {
            write { s ->
                val layout = change(s.layout)
                if (layout == s.layout || s.access.locked) s else s.copy(layout = layout, pending = s.access.shared && s.access.editable)
            }
        }
        if (next.pending) mutex.withLock { push(next) }
    }

    /**
     * Sends [s]'s order (with the entries only other devices have, from
     * [StoredTabLayout.remote]). 409 `device_scope`: the device keeps it
     * after all; 403: an order this person may not change (the server's
     * stays); a failure keeps it pending.
     */
    private suspend fun push(s: StoredTabLayout) {
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isBlank()) return
        val remote = s.remote
        val outgoing = if (remote == null) s.layout else s.layout.copy(
            start = TabLayout.keepForeign(s.layout.start, remote.start, StartLayout::identity),
            familie = TabLayout.keepForeign(s.layout.familie, remote.familie),
        )
        try {
            val response = withTimeoutOrNull(TIMEOUT_MS) {
                apiClientProvider.getClient(serverUrl).putClientLayout(ClientLayoutRequest(outgoing.toDto()))
            }
            when {
                response == null -> write { it.copy(pending = true, access = s.access, remote = s.remote) }
                response.isSuccessful -> write { it.copy(remote = outgoing, access = s.access, pending = it.layout != s.layout) }
                response.code() == 409 -> write { it.copy(access = s.access.copy(scope = LayoutScope.DEVICE), remote = null, pending = false) }
                response.code() == 403 -> write {
                    it.copy(layout = s.remote ?: it.layout, access = s.access.copy(editable = false), pending = false)
                }
                response.code() == 404 -> write { it.copy(access = LayoutAccess(server = false), remote = null, pending = false) }
                else -> {
                    Timber.d("Tab layout not saved: HTTP %d", response.code())
                    write { it.copy(pending = true, access = s.access, remote = s.remote) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Tab layout not saved (%s)", e.javaClass.simpleName)
            write { it.copy(pending = true, access = s.access, remote = s.remote) }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
        const val MIN_REFRESH_MS = 15_000L
    }
}
