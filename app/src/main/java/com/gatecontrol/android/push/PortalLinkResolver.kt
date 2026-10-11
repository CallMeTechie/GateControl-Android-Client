package com.gatecontrol.android.push

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.getPortalLink
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * URL for `open_portal` and the portal buttons of the notification center:
 * the portal's one-time auto-login link (the same flow as "Portal öffnen"
 * on the start screen) with the target tab as `next`, so the user lands on
 * it signed in. The server checks `next` (portal tabs only, else `/portal`);
 * servers that predate it ignore it and land on the portal home. When no
 * link can be minted, the plain portal URL + path is used (on the portal's
 * own origin only). Null when no portal is known.
 */
@Singleton
class PortalLinkResolver @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    suspend fun resolve(path: String?): String? {
        val portal = setupRepository.getPortalUrl()?.takeIf { it.startsWith("https://") } ?: return null
        val serverUrl = setupRepository.getServerUrl()
        val link = if (serverUrl.isEmpty()) {
            null
        } else {
            try {
                apiClientProvider.getClient(serverUrl).getPortalLink(portal, next = path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Push: portal link unavailable (%s)", e.javaClass.simpleName)
                null
            }
        }
        return link ?: withPath(portal, path)
    }

    companion object {
        /** Settings › Benachrichtigungen: "Meine Benachrichtigungen" in the portal (quiet hours, subscriptions). */
        const val NOTIFICATION_SETTINGS = "/portal#benachrichtigungen"
    }

    internal fun withPath(portal: String, path: String?): String {
        if (path.isNullOrBlank() || !path.startsWith("/") || path.startsWith("//")) return portal
        val base = portal.toHttpUrlOrNull() ?: return portal
        val joined = (base.scheme + "://" + base.host + (if (base.port != 443) ":${base.port}" else "") + path).toHttpUrlOrNull()
        return if (joined != null && joined.host == base.host && joined.scheme == base.scheme) joined.toString() else portal
    }
}
