package com.gatecontrol.android.media.server

import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.jellyfin.JellyfinClient
import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import kotlinx.coroutines.delay
import timber.log.Timber

/** How the automatic sign-in at a Jellyfin source ended. */
sealed interface MediaPairEnd {
    data object Paired : MediaPairEnd

    /** Why not: a code of the plugin's table (`no_mapping`, `quick_connect_off`, `rate_limited`, …) or [OFFLINE] / [BLOCKED] / [FAILED]. */
    data class Problem(val code: String) : MediaPairEnd

    companion object {
        const val OFFLINE = "offline"
        const val BLOCKED = "vpn_required"
        const val FAILED = "failed"
    }
}

/**
 * The automatic sign-in at a Jellyfin source of the server (Ablauf A of the plugin „Medien“, instead of the Quick
 * Connect code on the screen and „Gerät anmelden“): this device starts Quick Connect at Jellyfin with the DeviceId and
 * name the plugin gave it (the header values URL-encoded), sends the code to the plugin (`v1/jellyfin/pair`), which
 * confirms it for the linked user; then the device fetches its own access (`AuthenticateWithQuickConnect`), keeps it
 * encrypted and tells the plugin (`v1/jellyfin/paired`). No code is shown, nothing is typed. The server never sees
 * the access. At home through the LAN, away through the VPN (or the admin's HTTPS address), as every other call.
 */
class MediaAutoPair(
    private val hub: MediaHub,
    private val api: MediaServerApi,
    private val pollMs: Long = POLL_MS,
    private val waitMs: Long = WAIT_MS,
) {
    suspend fun pair(source: MediaSource): MediaPairEnd {
        val remoteId = source.remoteId ?: return MediaPairEnd.Problem(MediaPairEnd.FAILED)
        if (source.kind != MediaSourceKind.JELLYFIN || source.deviceId == null) return MediaPairEnd.Problem(MediaPairEnd.FAILED)
        source.problem?.let { return MediaPairEnd.Problem(it) }
        if (source.linkedUserId == null) return MediaPairEnd.Problem("no_mapping")
        if (hub.route(source) == MediaRoute.BLOCKED) return MediaPairEnd.Problem(MediaPairEnd.BLOCKED)
        var codeRetry = true
        var mismatchRetry = true
        while (true) {
            val signIn = hub.signIn(source)
            val q = when (val r = signIn.quickConnect()) {
                is MediaResult.Ok -> r.value
                // 401 „Quick connect is disabled“
                MediaResult.Unauthorized -> return MediaPairEnd.Problem("quick_connect_off")
                MediaResult.Offline -> return MediaPairEnd.Problem(MediaPairEnd.OFFLINE)
                else -> return MediaPairEnd.Problem(MediaPairEnd.FAILED)
            }
            when (val r = api.pair(remoteId, q.code)) {
                is MediaServerResult.Ok -> Unit
                is MediaServerResult.Refused -> when (r.error.code) {
                    // the code ran out or was used: a new one, once
                    "code_invalid" -> if (codeRetry) {
                        codeRetry = false
                        continue
                    } else {
                        return MediaPairEnd.Problem(r.error.code)
                    }
                    // Jellyfin made another device than ours: again with exactly the plugin's device.id, once
                    "device_mismatch" -> if (mismatchRetry) {
                        mismatchRetry = false
                        continue
                    } else {
                        return MediaPairEnd.Problem(r.error.code)
                    }
                    MediaServerError.OFFLINE -> return MediaPairEnd.Problem(MediaPairEnd.OFFLINE)
                    else -> return MediaPairEnd.Problem(r.error.code)
                }
            }
            return finish(source, remoteId, signIn, q)
        }
    }

    private suspend fun finish(source: MediaSource, remoteId: String, signIn: JellyfinSignIn, q: com.gatecontrol.android.media.jellyfin.JellyfinQuickConnect): MediaPairEnd {
        // confirmed by the plugin: Jellyfin keeps the request for a minute after that
        val until = System.currentTimeMillis() + waitMs
        var confirmed = false
        while (System.currentTimeMillis() < until) {
            val c = signIn.confirmed(q)
            if (c is MediaResult.Ok && c.value) {
                confirmed = true
                break
            }
            if (c !is MediaResult.Ok && c != MediaResult.Offline) break
            delay(pollMs)
        }
        if (!confirmed) return MediaPairEnd.Problem(MediaPairEnd.FAILED)
        val auth = when (val r = signIn.signIn(q)) {
            is MediaResult.Ok -> r.value
            MediaResult.Offline -> return MediaPairEnd.Problem(MediaPairEnd.OFFLINE)
            else -> return MediaPairEnd.Problem(MediaPairEnd.FAILED)
        }
        hub.store.setAccess(source.id, auth.token, auth.userId, auth.userName)
        hub.forget(source.id)
        // the Geräte-Reiter shows „angemeldet“; when it does not arrive, the access works all the same
        runCatching { api.paired(remoteId, source.deviceId ?: return@runCatching) }
        Timber.i("Medien: signed in at %s automatically", remoteId)
        return MediaPairEnd.Paired
    }

    /**
     * A Jellyfin access made on this device before the plugin (taken over): signed out at the server, the plugin's own
     * access replaces it. Best effort.
     */
    suspend fun signOutOld(old: MediaSource) {
        val token = old.token ?: return
        runCatching {
            JellyfinClient(old.address, hub.http(old), hub.info, token).post("Sessions/Logout")
        }
    }

    companion object {
        const val POLL_MS = 1_000L
        const val WAIT_MS = 60_000L
    }
}
