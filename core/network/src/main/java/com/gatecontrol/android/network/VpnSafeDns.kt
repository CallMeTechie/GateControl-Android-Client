package com.gatecontrol.android.network

import android.content.SharedPreferences
import okhttp3.Dns
import timber.log.Timber
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Public addresses of a host that worked before, kept across app restarts
 * (last resort of [VpnSafeDns] when no DNS answers with a usable address).
 */
interface DnsLastKnown {
    fun get(hostname: String): List<InetAddress>?
    fun put(hostname: String, addresses: List<InetAddress>)
}

/**
 * Name resolution of the GateControl server while the WireGuard tunnel may
 * be up. The server's resolver in the tunnel (dnsmasq on the gateway)
 * answers the server's own name with its VPN address (split horizon, see
 * the server's entrypoint.sh): API traffic of a tunneled app is meant to go
 * to the gateway through the tunnel — the public address seen from inside
 * the tunnel often does not answer at all.
 *
 * So the rule follows the route of this app's own traffic, not a guess:
 *  * the app goes through a VPN (its default network is the VPN — the
 *    tunnel without a per-app exception for GateControl): the VPN address
 *    is the way in and is used first, known public addresses follow;
 *  * the app is left out of the tunnel (split tunnel: excluded, or not in
 *    the include list) or no tunnel is up: a VPN address is unreachable and
 *    dropped; when nothing else is left the name is resolved over the
 *    network outside the VPN ([outsideVpn]), then from the addresses of
 *    this run ([preResolve], earlier answers) and finally from
 *    [lastKnown], which survives a restart of the app.
 *
 * Pure apart from the functions passed in, unit-tested.
 */
class VpnSafeDns(
    private val system: (String) -> List<InetAddress>,
    private val isVpnInternal: (InetAddress) -> Boolean,
    private val routedThroughVpn: () -> Boolean,
    private val outsideVpn: (String) -> List<InetAddress>,
    private val lastKnown: DnsLastKnown,
) : Dns {

    /** Public addresses learned in this run (cleared on disconnect, see [clear]). */
    private val fresh = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        val answered = try {
            system(hostname)
        } catch (_: Exception) {
            emptyList()
        }
        val (internal, public) = answered.partition(isVpnInternal)
        if (public.isNotEmpty()) remember(hostname, public)
        if (internal.isEmpty() && public.isNotEmpty()) return public

        if (internal.isNotEmpty() && appUsesVpn()) {
            // Split horizon: the tunnel's resolver points this app to the gateway through the tunnel.
            return (internal + public + known(hostname)).distinctBy { it.hostAddress }
        }
        if (public.isNotEmpty()) return public

        fresh[hostname]?.takeIf { it.isNotEmpty() }?.let { return it }
        resolveOutsideVpn(hostname)?.let { return it }
        lastKnown.get(hostname)?.takeIf { it.isNotEmpty() }?.let {
            Timber.i("DNS for %s: using the last known public address", hostname)
            return it
        }
        throw UnknownHostException(
            if (internal.isNotEmpty()) {
                "DNS lookup failed for $hostname (system DNS returned only VPN-internal addresses, not usable outside the tunnel; no public address known)"
            } else {
                "DNS lookup failed for $hostname (no answer, no public address known)"
            },
        )
    }

    /**
     * Resolve [hostname] before the tunnel comes up (or again later) and keep
     * its public addresses. A VPN-internal answer is never kept: then the
     * network outside the VPN is asked. Keeps what was known on failure.
     */
    fun preResolve(hostname: String) {
        val answered = try {
            system(hostname)
        } catch (e: Exception) {
            Timber.d("DNS pre-resolve of %s: %s", hostname, e.message)
            emptyList()
        }
        val public = answered.filterNot(isVpnInternal).ifEmpty { resolveOutsideVpn(hostname).orEmpty() }
        if (public.isEmpty()) {
            if (fresh.containsKey(hostname) || lastKnown.get(hostname) != null) {
                Timber.d("DNS pre-resolve of %s gave no public address, keeping the known one", hostname)
            } else {
                Timber.w("DNS pre-resolve failed for %s", hostname)
            }
            return
        }
        val prev = fresh[hostname]?.map { it.hostAddress }
        remember(hostname, public)
        val resolved = public.map { it.hostAddress }
        if (prev != null && prev != resolved) {
            Timber.i("DNS changed for %s: %s -> %s", hostname, prev, resolved)
        } else {
            Timber.d("DNS pre-resolved: %s -> %s", hostname, resolved)
        }
    }

    /** Forget the addresses of this run (on disconnect); the last known ones stay as the last resort. */
    fun clear() {
        fresh.clear()
    }

    private fun appUsesVpn(): Boolean = try {
        routedThroughVpn()
    } catch (_: Exception) {
        false
    }

    private fun known(hostname: String): List<InetAddress> =
        fresh[hostname] ?: lastKnown.get(hostname).orEmpty()

    private fun resolveOutsideVpn(hostname: String): List<InetAddress>? {
        val found = try {
            outsideVpn(hostname).filterNot(isVpnInternal)
        } catch (_: Exception) {
            emptyList()
        }
        if (found.isEmpty()) return null
        Timber.i("DNS for %s resolved outside the VPN", hostname)
        remember(hostname, found)
        return found
    }

    private fun remember(hostname: String, addresses: List<InetAddress>) {
        fresh[hostname] = addresses
        try {
            lastKnown.put(hostname, addresses)
        } catch (_: Exception) {
        }
    }
}

/**
 * [DnsLastKnown] in SharedPreferences (one entry per host, the addresses as
 * IP literals). [prefs] is opened on first use; without it only this run
 * remembers.
 */
class PrefsDnsLastKnown(prefs: () -> SharedPreferences?) : DnsLastKnown {

    private val prefs by lazy {
        try {
            prefs()
        } catch (_: Exception) {
            null
        }
    }
    private val memory = ConcurrentHashMap<String, List<InetAddress>>()

    override fun get(hostname: String): List<InetAddress>? {
        memory[hostname]?.let { return it }
        val stored = try {
            prefs?.getString(KEY_PREFIX + hostname, null)
        } catch (_: Exception) {
            null
        }
        return decode(hostname, stored)?.also { memory[hostname] = it }
    }

    override fun put(hostname: String, addresses: List<InetAddress>) {
        if (addresses.isEmpty()) return
        val value = encode(addresses)
        if (get(hostname)?.let { encode(it) } == value) return
        memory[hostname] = addresses
        try {
            prefs?.edit()?.putString(KEY_PREFIX + hostname, value)?.apply()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val PREFS_NAME = "vpn_safe_dns"
        private const val KEY_PREFIX = "host:"
        private const val MAX_ADDRESSES = 8
        private val IPV4_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV6_RE = Regex("^[0-9a-fA-F:.]+$")

        fun encode(addresses: List<InetAddress>): String =
            addresses.mapNotNull { it.hostAddress?.substringBefore('%') }.distinct().take(MAX_ADDRESSES).joinToString(",")

        /** The stored literals of [hostname]; anything that is not an IP literal is skipped (never a DNS lookup). */
        fun decode(hostname: String, value: String?): List<InetAddress>? {
            if (value.isNullOrBlank()) return null
            val out = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_ADDRESSES).mapNotNull { literal ->
                val isLiteral = IPV4_RE.matches(literal) || (literal.contains(':') && IPV6_RE.matches(literal))
                if (!isLiteral) return@mapNotNull null
                try {
                    InetAddress.getByAddress(hostname, InetAddress.getByName(literal).address)
                } catch (_: Exception) {
                    null
                }
            }
            return out.ifEmpty { null }
        }
    }
}
