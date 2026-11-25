package com.gatecontrol.android.media

import com.gatecontrol.android.media.jellyfin.JellyfinAuth
import com.gatecontrol.android.media.jellyfin.JellyfinServerHint
import com.gatecontrol.android.media.jellyfin.JellyfinServerInfo
import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.UUID
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Why adding a source did not work (Medien › Quellen › Quelle hinzufügen). [OFFLINE]: nothing answered (timeout,
 * connection refused, no route); [OFFLINE_AWAY]: the same away, through the tunnel (does the gateway route the home
 * network?); [NOT_FOUND]: something answered, but no Jellyfin; [CERTIFICATE]: HTTPS with a
 * certificate the device does not trust (self-signed, another name).
 */
enum class MediaAddProblem { BAD_ADDRESS, NOT_FOUND, OLD_SERVER, NO_QUICK_CONNECT, WRONG_LOGIN, OFFLINE, OFFLINE_AWAY, CERTIFICATE, FAILED }

/** Which [MediaAddProblem] a connection error is. Pure, unit-tested. */
object MediaFailure {
    fun problemOf(e: IOException?): MediaAddProblem {
        if (e == null) return MediaAddProblem.OFFLINE
        if (causes(e).any { it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException }) {
            return MediaAddProblem.CERTIFICATE
        }
        return when {
            // http:// refused by the app's network security policy (cleartext)
            e is UnknownServiceException -> MediaAddProblem.FAILED
            e is SocketTimeoutException || e is ConnectException || e is NoRouteToHostException || e is UnknownHostException -> MediaAddProblem.OFFLINE
            // https:// to an http port and the like: something answered, but not as asked
            e is SSLException || e is ProtocolException -> MediaAddProblem.NOT_FOUND
            e.message?.contains("unexpected end of stream", ignoreCase = true) == true -> MediaAddProblem.NOT_FOUND
            else -> MediaAddProblem.OFFLINE
        }
    }

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { t -> t.cause?.takeIf { it !== t } }.take(8)
}

/**
 * The server search: the servers that answered, and [away] when none could (no Wi-Fi / Ethernet, or the server's
 * home-network check says this device is not at home): the broadcast only reaches the home network itself, so away
 * the address is typed and goes through the tunnel.
 */
data class MediaSearch(val servers: List<JellyfinServerHint>, val away: Boolean)

/** Is a Jellyfin server at the address? */
sealed interface MediaServerCheck {
    /** Found at [address] (the base URL it answered at). */
    data class Found(val server: JellyfinServerInfo, val address: String) : MediaServerCheck

    data class Problem(val problem: MediaAddProblem) : MediaServerCheck
}

/** "Verbinden" of a share: kept (and paired with [partner], the same folder as SMB and as WebDAV), or why not. */
sealed interface MediaShareAdded {
    data class Saved(val source: MediaSource, val partner: MediaSource?) : MediaShareAdded

    data class Problem(val problem: MediaAddProblem) : MediaShareAdded
}

/** Quick Connect on this device: signed in, or why not. */
sealed interface MediaQuickConnectEnd {
    data class SignedIn(val auth: MediaResult<JellyfinAuth>) : MediaQuickConnectEnd

    data class Problem(val problem: MediaAddProblem) : MediaQuickConnectEnd
}

/**
 * Adding a source, the steps without UI state (the page keeps where it is): the search in the home network, the check
 * of a Jellyfin address, Quick Connect (the code here, confirmed in Jellyfin or by another GateControl), name and
 * password, a share's first read; then the source kept, encrypted. Shared by the TV's and the phone's pages.
 */
class MediaSetup(private val hub: MediaHub) {

    /** Jellyfin servers that answer in the home network; none when the search fails. */
    suspend fun search(): List<JellyfinServerHint> = runCatching { hub.discover() }.getOrDefault(emptyList())

    /** [search], and when nothing answered whether this device is away (then that is why). */
    suspend fun searchHome(): MediaSearch {
        val servers = search()
        if (servers.isNotEmpty()) return MediaSearch(servers, away = false)
        hub.check(null)
        return MediaSearch(emptyList(), away = !hub.hasLan() || hub.link.value.atHome == false)
    }

    /** Step 2: is a Jellyfin server (new enough) at [typed] ("192.168.1.20", "http://nas:8096")? */
    suspend fun check(typed: String): MediaServerCheck {
        val base = MediaAddress.normalize(typed) ?: return MediaServerCheck.Problem(MediaAddProblem.BAD_ADDRESS)
        hub.check(null)
        val signIn = hub.signIn(base)
        return when (val r = signIn.server()) {
            is MediaResult.Ok ->
                if (r.value.supported) MediaServerCheck.Found(r.value, base) else MediaServerCheck.Problem(MediaAddProblem.OLD_SERVER)
            MediaResult.Offline -> {
                val problem = MediaFailure.problemOf(signIn.lastFailure)
                // away the address goes through the tunnel: not reachable there is most often the gateway's routes
                val away = problem == MediaAddProblem.OFFLINE && hub.link.value.atHome == false
                MediaServerCheck.Problem(if (away) MediaAddProblem.OFFLINE_AWAY else problem)
            }
            else -> MediaServerCheck.Problem(MediaAddProblem.NOT_FOUND)
        }
    }

    /**
     * Quick Connect at [base]: the code for [onCode] (null while a new one is asked for, after the old one ran out on
     * the server), then waiting until it is confirmed (Jellyfin on the phone or PC, or "Gerät anmelden" of another
     * GateControl); then signed in. [onSigningIn] once it was confirmed. Ends after [QUICK_CONNECT_MS] at the latest.
     */
    suspend fun quickConnect(base: String, onCode: (String?) -> Unit, onSigningIn: () -> Unit = {}): MediaQuickConnectEnd {
        val signIn = hub.signIn(base)
        while (true) {
            if (!signIn.quickConnectEnabled()) return MediaQuickConnectEnd.Problem(MediaAddProblem.NO_QUICK_CONNECT)
            val q = signIn.quickConnect().valueOrNull() ?: return MediaQuickConnectEnd.Problem(MediaAddProblem.FAILED)
            onCode(q.code)
            val until = System.currentTimeMillis() + QUICK_CONNECT_MS
            var expired = false
            while (!expired && System.currentTimeMillis() < until) {
                delay(QUICK_POLL_MS)
                when (val c = signIn.confirmed(q)) {
                    is MediaResult.Ok -> if (c.value) {
                        onSigningIn()
                        return MediaQuickConnectEnd.SignedIn(signIn.signIn(q))
                    }
                    MediaResult.Offline -> Unit
                    // the code ran out on the server: a new one
                    else -> expired = true
                }
            }
            if (!expired) return MediaQuickConnectEnd.Problem(MediaAddProblem.FAILED)
            onCode(null)
        }
    }

    /** Name and password at [base]: sent once, never kept. */
    suspend fun signIn(base: String, name: String, password: String): MediaResult<JellyfinAuth> =
        hub.signIn(base).signIn(name.trim(), password)

    /** Why a sign-in did not work. */
    fun problemOf(r: MediaResult<*>): MediaAddProblem = when (r) {
        MediaResult.Unauthorized -> MediaAddProblem.WRONG_LOGIN
        MediaResult.Offline -> MediaAddProblem.OFFLINE
        else -> MediaAddProblem.FAILED
    }

    /** Signed in at [address] (the server [server]): the source kept and shown. */
    fun saveJellyfin(auth: JellyfinAuth, server: JellyfinServerInfo?, address: String): MediaSource {
        val source = hub.store.save(
            MediaSource(
                id = UUID.randomUUID().toString(),
                kind = MediaSourceKind.JELLYFIN,
                name = server?.name ?: "Jellyfin",
                address = address,
                userId = auth.userId,
                userName = auth.userName,
                token = auth.token,
                serverId = auth.serverId ?: server?.id,
            ),
        )
        hub.select(source.id)
        return source
    }

    /**
     * "Verbinden" of a share: the address as typed (`\\nas\filme`, `smb://…`, `https://…:5006/filme`), the root read
     * once with the name and password, then kept (encrypted). The same folder as SMB and as WebDAV is paired.
     */
    suspend fun connectShare(kind: MediaSourceKind, typed: String, name: String, password: String): MediaShareAdded {
        val address = MediaAddress.normalizeShare(kind, typed) ?: return MediaShareAdded.Problem(MediaAddProblem.BAD_ADDRESS)
        val user = name.trim().ifEmpty { null }
        val pass = password.takeIf { user != null && it.isNotEmpty() }
        hub.check(null)
        val r = hub.tryShare(kind, address, user, pass)
        if (r !is MediaResult.Ok) {
            val problem = when (r) {
                MediaResult.Unauthorized -> MediaAddProblem.WRONG_LOGIN
                MediaResult.NotFound -> MediaAddProblem.NOT_FOUND
                MediaResult.Offline -> MediaAddProblem.OFFLINE
                else -> MediaAddProblem.FAILED
            }
            return MediaShareAdded.Problem(problem)
        }
        val draft = MediaSource(UUID.randomUUID().toString(), kind, "", address, userName = user, password = pass)
        val saved = hub.store.save(draft.copy(name = MediaLogic.shareAddress(draft)))
        val partner = saved.pairWith?.let { hub.store.source(it) }
        return MediaShareAdded.Saved(saved, partner)
    }

    /** "Gerät anmelden": another device's Quick Connect [code] confirmed with [source]'s access. */
    suspend fun authorize(source: MediaSource, code: String) = hub.authorize(source, code)

    companion object {
        const val QUICK_POLL_MS = 3_000L
        const val QUICK_CONNECT_MS = 10 * 60_000L
    }
}
