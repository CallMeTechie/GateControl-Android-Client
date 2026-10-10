package com.gatecontrol.android.service

import com.gatecontrol.android.common.ClientPolicy
import com.gatecontrol.android.data.ClientPolicyRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client policy from the server ("Client-Richtlinien"): fetch, cache and
 * apply what Android lets an app enforce.
 *
 * - [policy] is the last known policy, [ClientPolicy.UNRESTRICTED] when none
 *   was ever fetched. It survives restarts and applies offline.
 * - [refresh] asks the server (If-None-Match with the cached version, 304 =
 *   unchanged). Any failure keeps the last known policy.
 * - [noteVersion] is fed with the policyVersion of heartbeat/permissions
 *   answers and refreshes when it differs.
 * - [applyToSettings] forces the stored settings the policy fixes
 *   (auto-connect, allowed split-tunnel mode).
 */
@Singleton
class ClientPolicyManager @Inject constructor(
    private val repository: ClientPolicyRepository,
    private val settingsRepository: SettingsRepository,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshLock = Mutex()

    /** Effective policy (unrestricted until one was fetched). */
    val policy: StateFlow<ClientPolicy> = repository.cached()
        .map { it?.policy ?: ClientPolicy.UNRESTRICTED }
        .stateIn(scope, SharingStarted.Eagerly, ClientPolicy.UNRESTRICTED)

    /** Policy as stored right now (reads the store, not the eager state). */
    suspend fun current(): ClientPolicy = repository.current()?.policy ?: ClientPolicy.UNRESTRICTED

    enum class Result { UPDATED, UNCHANGED, UNAVAILABLE }

    suspend fun refresh(): Result = refreshLock.withLock {
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isBlank()) return Result.UNAVAILABLE
        val cached = repository.current()
        val response = try {
            apiClientProvider.getClient(serverUrl)
                .getClientPolicy(cached?.version?.let { "\"$it\"" })
        } catch (e: Exception) {
            Timber.d("Client policy fetch failed, keeping last known: %s", e.message)
            return Result.UNAVAILABLE
        }
        if (response.code() == 304) return Result.UNCHANGED
        val body = response.body()
        val p = body?.policy
        if (!response.isSuccessful || body == null || !body.ok || p == null) {
            // Old server without the endpoint (404), errors: keep what we have.
            return Result.UNAVAILABLE
        }
        val fresh = ClientPolicy.from(
            killSwitch = p.killSwitch,
            autoConnect = p.autoConnect,
            autostart = p.autostart,
            splitTunnelModes = p.splitTunnelModes,
            splitTunnelLocked = p.splitTunnelLocked,
            lockSettings = p.lockSettings,
            lockServer = p.lockServer,
        )
        val version = body.version?.takeIf { VERSION_RE.matches(it) }
        if (cached != null && cached.policy == fresh && cached.version == version) return Result.UNCHANGED
        repository.save(fresh, version)
        applyToSettings(fresh)
        Timber.i("Client policy %s (version %s)", if (cached == null) "loaded" else "updated", version ?: "-")
        Result.UPDATED
    }

    /** A server answer carried a policy version: refresh when it differs. */
    suspend fun noteVersion(version: String?): Result {
        if (version.isNullOrBlank() || !VERSION_RE.matches(version)) return Result.UNCHANGED
        if (repository.current()?.version == version) return Result.UNCHANGED
        return refresh()
    }

    fun noteVersionAsync(version: String?) {
        launchSafely { noteVersion(version) }
    }

    fun refreshAsync() {
        launchSafely { refresh() }
    }

    /** New server / token: the old server's policy must not stick. */
    suspend fun reset() {
        repository.clear()
        refresh()
    }

    /** Forces the stored settings the policy fixes. */
    suspend fun applyToSettings(policy: ClientPolicy) {
        policy.forcedAutoConnect?.let { forced ->
            if (settingsRepository.getAutoConnect().first() != forced) settingsRepository.setAutoConnect(forced)
        }
        if (!policy.splitTunnelLocked) {
            val mode = settingsRepository.getSplitTunnelMode().first()
            val clamped = policy.clampMode(mode)
            if (clamped != mode) {
                Timber.i("Client policy: split-tunnel mode %s not allowed, using %s", mode, clamped)
                settingsRepository.setSplitTunnelMode(clamped)
            }
        }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: Exception) { Timber.w(e, "Client policy task failed") }
        }
    }

    private companion object {
        val VERSION_RE = Regex("^[0-9a-fA-F]{1,64}$")
    }
}
