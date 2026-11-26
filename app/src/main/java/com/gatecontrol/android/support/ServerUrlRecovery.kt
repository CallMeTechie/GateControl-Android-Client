package com.gatecontrol.android.support

import android.content.Context
import com.gatecontrol.android.common.EnrollmentLink
import com.gatecontrol.android.common.LoginOwner
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.layout.StoredTabLayout
import com.gatecontrol.android.notify.PushPrefsStore
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts back the server address older versions dropped on a passing Keystore
 * error while the API token stayed.
 *
 * A candidate address (last push server, portal address, WireGuard endpoint
 * host) is only used when it is *proven* to be the lost one: data cached for
 * the login stores [LoginOwner.key] of server + token, so a candidate counts
 * only if its hash with the stored token equals such an owner key. Without a
 * match nothing is changed and the app asks for a new setup as before.
 */
@Singleton
class ServerUrlRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setup: SetupRepository,
    private val settings: SettingsRepository,
    private val push: PushPrefsStore,
) {

    /** Cheap check for app start: only a lost address with a kept token needs [recover]. */
    fun needed(): Boolean = !setup.hasStoredServerUrl() && setup.getApiToken().isNotBlank()

    suspend fun recover(): Boolean {
        if (!needed()) return false
        val token = setup.getApiToken()
        val candidates = ServerUrlRecoveryLogic.candidates(
            pushServer = runCatching { push.boundServer() }.getOrNull(),
            portalUrl = setup.getPortalUrl(),
            wireGuardConfig = setup.getWireGuardConfig(),
        )
        val owners = buildSet {
            runCatching { context.getSharedPreferences(PLUGINS_PREFS, Context.MODE_PRIVATE).getString(KEY_OWNER, null) }
                .getOrNull()?.let(::add)
            runCatching { StoredTabLayout.decode(settings.getTabLayout().first()).owner }.getOrNull()?.let(::add)
            runCatching {
                val f = File(File(context.filesDir, "door"), "me.json")
                if (f.exists()) JsonParser.parseString(f.readText()).asJsonObject.get("owner")?.asString else null
            }.getOrNull()?.let(::add)
        }
        val found = ServerUrlRecoveryLogic.verified(candidates, token, owners)
        if (found == null) {
            Timber.w("Setup: server address missing, %d candidates, none verified — new setup needed", candidates.size)
            return false
        }
        val restored = setup.restoreServerUrl(found)
        if (restored) Timber.i("Setup: lost server address restored (verified by login hash)")
        return restored
    }

    private companion object {
        const val PLUGINS_PREFS = "familie_plugins"
        const val KEY_OWNER = "owner"
    }
}

/** Pure part of [ServerUrlRecovery]. */
object ServerUrlRecoveryLogic {

    /** Possible spellings of the lost address, most likely first, without duplicates. */
    fun candidates(pushServer: String?, portalUrl: String?, wireGuardConfig: String?): List<String> {
        val out = LinkedHashSet<String>()
        fun addVariants(raw: String?) {
            val v = raw?.trim().orEmpty()
            if (v.isEmpty()) return
            out += v
            out += v.trimEnd('/')
            EnrollmentLink.normalizeServerUrl(v)?.let { out += it }
        }
        addVariants(pushServer)
        portalUrl?.let { origin(it) }?.let(::addVariants)
        endpointHost(wireGuardConfig)?.let { host -> addVariants("https://$host") }
        return out.filter { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** The candidate whose login hash with [token] is one of [owners]; null when none matches. */
    fun verified(candidates: List<String>, token: String, owners: Set<String>): String? {
        if (token.isBlank()) return null
        val known = owners.filter { it.isNotBlank() }.toSet()
        if (known.isEmpty()) return null
        return candidates.firstOrNull { LoginOwner.key(it, token) in known }
    }

    private fun origin(url: String): String? = runCatching {
        val u = URI(url.trim())
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host ?: return null
        val port = if (u.port > 0) ":${u.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()

    /** Host of the first `Endpoint = host:port` line of a WireGuard config. */
    fun endpointHost(config: String?): String? {
        val line = config?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.substringBefore('=').trim().equals("Endpoint", ignoreCase = true) && it.contains('=') }
            ?: return null
        val value = line.substringAfter('=').trim()
        val host = if (value.startsWith("[")) value.substringAfter('[').substringBefore(']') else value.substringBeforeLast(':')
        return host.trim().takeIf { it.isNotEmpty() && !it.contains(' ') }
    }
}
