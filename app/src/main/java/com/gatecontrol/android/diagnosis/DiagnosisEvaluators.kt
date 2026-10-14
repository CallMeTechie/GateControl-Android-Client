package com.gatecontrol.android.diagnosis

import com.gatecontrol.android.R
import com.gatecontrol.android.common.Formatters
import com.gatecontrol.android.tunnel.TunnelState
import retrofit2.HttpException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/*
 * Pure evaluation of the diagnosis checks: measured facts in, CheckResult
 * out. Kept free of Android APIs so every rule is unit-tested.
 */

// ─── 1. internet ─────────────────────────────────────────────────────────

enum class Transport(val key: String) { WIFI("wifi"), CELLULAR("cellular"), ETHERNET("ethernet"), OTHER("other") }

/** The device's network (the one under the VPN when the VPN is up). */
data class InternetState(val connected: Boolean, val validated: Boolean, val transport: Transport)

internal fun transportName(t: Transport): DiagText = DiagText(
    when (t) {
        Transport.WIFI -> R.string.diag_transport_wifi
        Transport.CELLULAR -> R.string.diag_transport_cellular
        Transport.ETHERNET -> R.string.diag_transport_ethernet
        Transport.OTHER -> R.string.diag_transport_other
    },
)

fun evaluateInternet(state: InternetState?): CheckResult = when {
    state == null || !state.connected -> CheckResult(
        CheckId.INTERNET, CheckStatus.FAIL,
        detail = DiagText(R.string.diag_internet_none),
        value = "none",
        hint = DiagText(R.string.diag_internet_none_hint),
    )
    !state.validated -> CheckResult(
        CheckId.INTERNET, CheckStatus.FAIL,
        detail = DiagText(R.string.diag_internet_not_validated, transportName(state.transport)),
        value = "${state.transport.key}, not validated",
        hint = DiagText(R.string.diag_internet_not_validated_hint),
    )
    else -> CheckResult(
        CheckId.INTERNET, CheckStatus.OK,
        detail = DiagText(R.string.diag_internet_ok, transportName(state.transport)),
        value = state.transport.key,
    )
}

// ─── 2. server ───────────────────────────────────────────────────────────

const val LATENCY_OK_MS = 150L
const val LATENCY_SLOW_MS = 600L

enum class ServerErrorKind { DNS, TIMEOUT, CONNECT, TLS, AUTH, HTTP, OTHER }

/** Error class of a failed request, in the terms the hint can explain. */
fun classifyError(t: Throwable): ServerErrorKind {
    var e: Throwable? = t
    var depth = 0
    while (e != null && depth < 6) {
        when (e) {
            is HttpException -> return if (e.code() == 401 || e.code() == 403) ServerErrorKind.AUTH else ServerErrorKind.HTTP
            is UnknownHostException -> return ServerErrorKind.DNS
            is SSLException, is CertificateException -> return ServerErrorKind.TLS
            is SocketTimeoutException -> return ServerErrorKind.TIMEOUT
            is ConnectException, is NoRouteToHostException -> return ServerErrorKind.CONNECT
            is InterruptedIOException -> return ServerErrorKind.TIMEOUT
        }
        e = e.cause
        depth++
    }
    return ServerErrorKind.OTHER
}

fun median(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/** ok < 150 ms, warn below 600 ms ("a bit slow"), warn above ("slow"). */
fun classifyLatency(ms: Long): CheckResult = when {
    ms < LATENCY_OK_MS -> CheckResult(CheckId.SERVER, CheckStatus.OK, DiagText(R.string.diag_server_ms, ms), "$ms ms")
    ms < LATENCY_SLOW_MS -> CheckResult(
        CheckId.SERVER, CheckStatus.WARN, DiagText(R.string.diag_server_ms_elevated, ms), "$ms ms",
        DiagText(R.string.diag_server_slow_hint),
    )
    else -> CheckResult(
        CheckId.SERVER, CheckStatus.WARN, DiagText(R.string.diag_server_ms_slow, ms), "$ms ms",
        DiagText(R.string.diag_server_slow_hint),
    )
}

/** Median of the successful pings, or the first error in plain words. */
fun evaluateServer(latencies: List<Long>, error: Throwable?): CheckResult {
    median(latencies)?.let { return classifyLatency(it) }
    val e = error ?: SocketTimeoutException()
    val code = (e as? HttpException)?.code()
    val kind = classifyError(e)
    val (detail, hint) = when (kind) {
        ServerErrorKind.DNS -> DiagText(R.string.diag_server_err_dns) to DiagText(R.string.diag_server_err_dns_hint)
        ServerErrorKind.TIMEOUT -> DiagText(R.string.diag_server_err_timeout) to DiagText(R.string.diag_server_err_unreachable_hint)
        ServerErrorKind.CONNECT -> DiagText(R.string.diag_server_err_connect) to DiagText(R.string.diag_server_err_unreachable_hint)
        ServerErrorKind.TLS -> DiagText(R.string.diag_server_err_tls) to DiagText(R.string.diag_server_err_tls_hint)
        ServerErrorKind.AUTH -> DiagText(R.string.diag_server_err_auth, code ?: 0) to DiagText(R.string.diag_server_err_auth_hint)
        ServerErrorKind.HTTP -> DiagText(R.string.diag_server_err_http, code ?: 0) to DiagText(R.string.diag_server_err_http_hint)
        ServerErrorKind.OTHER -> DiagText(R.string.diag_server_err_other, e.javaClass.simpleName) to DiagText(R.string.diag_server_err_unreachable_hint)
    }
    val value = if (code != null) "HTTP $code" else e.javaClass.simpleName
    return CheckResult(CheckId.SERVER, CheckStatus.FAIL, detail, value, hint)
}

fun serverNotConfigured(): CheckResult = CheckResult(
    CheckId.SERVER, CheckStatus.FAIL, DiagText(R.string.diag_server_not_configured), "not configured",
    DiagText(R.string.diag_server_not_configured_hint),
)

// ─── 3. tunnel ───────────────────────────────────────────────────────────

fun evaluateTunnel(state: TunnelState, nowMillis: Long): CheckResult = when (state) {
    is TunnelState.Connected -> {
        val sec = ((nowMillis - state.connectedSince) / 1000).coerceAtLeast(0)
        CheckResult(
            CheckId.TUNNEL, CheckStatus.OK,
            DiagText(R.string.diag_tunnel_connected_since, Formatters.formatDuration(sec)),
            "connected ${sec}s",
        )
    }
    is TunnelState.Error -> CheckResult(
        CheckId.TUNNEL, CheckStatus.FAIL,
        DiagText(R.string.diag_tunnel_error, state.message),
        "error",
        DiagText(R.string.diag_tunnel_error_hint),
    )
    // Off is a choice, not a fault: the checks that need the tunnel are skipped too.
    TunnelState.Disconnected -> CheckResult(
        CheckId.TUNNEL, CheckStatus.SKIPPED,
        DiagText(R.string.diag_tunnel_off),
        "disconnected",
        DiagText(R.string.diag_tunnel_off_hint),
    )
    else -> CheckResult(
        CheckId.TUNNEL, CheckStatus.WARN,
        DiagText(R.string.diag_tunnel_busy),
        state.javaClass.simpleName.lowercase(),
        DiagText(R.string.diag_tunnel_busy_hint),
    )
}

/** For every check that needs the tunnel while it is down. */
fun skippedNoTunnel(id: CheckId): CheckResult =
    CheckResult(id, CheckStatus.SKIPPED, DiagText(R.string.diag_skipped), hint = DiagText(R.string.diag_skipped_no_tunnel_hint))

// ─── 4. handshake ────────────────────────────────────────────────────────

const val HANDSHAKE_OK_SEC = 180L
const val HANDSHAKE_WARN_SEC = 600L

/** Tunnel statistics at [atMillis]; [lastHandshakeEpochSec] 0 = no handshake yet. */
data class HandshakeSample(val lastHandshakeEpochSec: Long, val rxBytes: Long, val atMillis: Long) {
    /** Seconds since the last handshake, null when there was none. */
    val ageSec: Long? get() = if (lastHandshakeEpochSec <= 0) null else (atMillis / 1000 - lastHandshakeEpochSec).coerceAtLeast(0)

    /** Older than the ok limit: generate traffic and look again before judging. */
    val needsRecheck: Boolean get() = ageSec.let { it == null || it > HANDSHAKE_OK_SEC }
}

/**
 * ok ≤ 180 s, warn ≤ 600 s, fail beyond (or never). [second] is the sample
 * after traffic was generated; when rx did not grow then, nothing came back.
 */
fun evaluateHandshake(first: HandshakeSample, second: HandshakeSample?): CheckResult {
    val last = second ?: first
    val age = last.ageSec
    val rxStalled = second != null && second.rxBytes <= first.rxBytes
    val status = when {
        age == null -> CheckStatus.FAIL
        age <= HANDSHAKE_OK_SEC -> CheckStatus.OK
        age <= HANDSHAKE_WARN_SEC -> CheckStatus.WARN
        else -> CheckStatus.FAIL
    }
    val detail = when {
        age == null -> DiagText(R.string.diag_handshake_never)
        age < 120 -> DiagText(R.string.diag_handshake_ago_s, age)
        else -> DiagText(R.string.diag_handshake_ago_min, age / 60)
    }
    val value = buildString {
        append(if (age == null) "never" else "${age}s")
        if (second != null) append(if (rxStalled) ", rechecked, rx unchanged" else ", rechecked")
    }
    val hint = when {
        status == CheckStatus.OK -> null
        rxStalled -> DiagText(R.string.diag_handshake_hint_no_rx)
        else -> DiagText(R.string.diag_handshake_hint)
    }
    return CheckResult(CheckId.HANDSHAKE, status, detail, value, hint)
}

// ─── 5. dns ──────────────────────────────────────────────────────────────

/** DNS of the VPN network (LinkProperties) and the private DNS setting. */
data class VpnDnsState(
    val vpnNetworkFound: Boolean,
    val dnsServers: List<String>,
    val privateDnsActive: Boolean = false,
    /** Set only in strict mode ("hostname of private DNS provider"). */
    val privateDnsServerName: String? = null,
)

/** "10.8.0.1, 10.8.0.2" → normalised addresses. */
fun parseDnsList(raw: String?): List<String> =
    raw.orEmpty().split(',', ' ', ';').map(::normalizeIp).filter { it.isNotEmpty() }.distinct()

internal fun normalizeIp(s: String): String = s.trim().removePrefix("/").substringBefore('%').lowercase()

/**
 * ok when the VPN's resolvers are exactly the server's; fail when none of
 * them is (queries bypass GateControl); warn for extra resolvers or strict
 * private DNS (Android then asks a third-party resolver).
 */
fun evaluateDns(state: VpnDnsState, serverDns: List<String>?): CheckResult {
    if (serverDns == null) {
        return CheckResult(CheckId.DNS, CheckStatus.WARN, DiagText(R.string.diag_dns_unknown), null, DiagText(R.string.diag_dns_unknown_hint))
    }
    if (!state.vpnNetworkFound) {
        return CheckResult(CheckId.DNS, CheckStatus.WARN, DiagText(R.string.diag_dns_no_vpn_network), null, DiagText(R.string.diag_dns_no_vpn_network_hint))
    }
    val vpn = state.dnsServers.map(::normalizeIp).filter { it.isNotEmpty() }.distinct()
    val expected = serverDns.map(::normalizeIp).toSet()
    val value = buildString {
        append(vpn.joinToString(", ").ifEmpty { "none" })
        append(" (server: ").append(expected.joinToString(", ")).append(")")
        state.privateDnsServerName?.let { append(", private DNS: ").append(it) }
    }
    if (vpn.isEmpty()) {
        return CheckResult(CheckId.DNS, CheckStatus.FAIL, DiagText(R.string.diag_dns_none), value, DiagText(R.string.diag_dns_leak_hint))
    }
    val foreign = vpn.filter { it !in expected }
    if (foreign.size == vpn.size) {
        return CheckResult(
            CheckId.DNS, CheckStatus.FAIL,
            DiagText(R.string.diag_dns_mismatch, foreign.joinToString(", ")), value,
            DiagText(R.string.diag_dns_leak_hint),
        )
    }
    val privateName = state.privateDnsServerName?.takeIf { it.isNotBlank() }
    if (privateName != null) {
        return CheckResult(
            CheckId.DNS, CheckStatus.WARN,
            DiagText(R.string.diag_dns_private, privateName), value,
            DiagText(R.string.diag_dns_private_hint, privateName),
        )
    }
    if (foreign.isNotEmpty()) {
        return CheckResult(
            CheckId.DNS, CheckStatus.WARN,
            DiagText(R.string.diag_dns_partial, foreign.joinToString(", ")), value,
            DiagText(R.string.diag_dns_partial_hint),
        )
    }
    return CheckResult(CheckId.DNS, CheckStatus.OK, DiagText(R.string.diag_dns_ok, vpn.joinToString(", ")), value)
}

// ─── 6. mtu ──────────────────────────────────────────────────────────────

const val DEFAULT_TUNNEL_MTU = 1420
const val MIN_PROBE_MTU = 1280
const val MTU_MAX_PROBES = 8

/** IPv4 header (20) + ICMP header (8): ping -s payload = size − 28. */
const val ICMP_IPV4_OVERHEAD = 28

enum class PingOutcome { REPLY, NO_REPLY, UNAVAILABLE }

/** Exit code null = killed after the timeout; [started] false = binary missing. */
data class CommandResult(val exitCode: Int?, val output: String, val started: Boolean = true)

/** iputils ping: 0 reply, 1 no reply (incl. "message too long"), else an error. */
fun parsePing(result: CommandResult): PingOutcome = when {
    !result.started -> PingOutcome.UNAVAILABLE
    result.exitCode == null -> PingOutcome.NO_REPLY
    result.exitCode == 0 -> PingOutcome.REPLY
    result.exitCode == 1 -> PingOutcome.NO_REPLY
    result.output.contains("message too long", ignoreCase = true) -> PingOutcome.NO_REPLY
    else -> PingOutcome.UNAVAILABLE
}

sealed interface MtuSearch {
    /** The full tunnel MTU gets through. */
    data class Full(val mtu: Int) : MtuSearch
    /** Only packets up to [largest] get through. */
    data class Reduced(val largest: Int, val mtu: Int) : MtuSearch
    /** Not even [MIN_PROBE_MTU] got an answer: ICMP is filtered, nothing to conclude. */
    data object NoAnswer : MtuSearch
    /** No usable ping on this device. */
    data object Unavailable : MtuSearch
    /** Ran out of time before anything was known. */
    data object Inconclusive : MtuSearch
}

/**
 * Largest packet size (IP total length) between [minMtu] and [tunnelMtu]
 * that gets an answer: the tunnel MTU first (retried once, a single lost
 * reply must not count), then [minMtu], then a binary search — at most
 * [maxProbes] probes, stopping early when [hasTime] says so.
 */
suspend fun searchMtu(
    tunnelMtu: Int,
    minMtu: Int = MIN_PROBE_MTU,
    maxProbes: Int = MTU_MAX_PROBES,
    hasTime: () -> Boolean = { true },
    probe: suspend (size: Int) -> PingOutcome,
): MtuSearch {
    var probes = 0
    suspend fun test(size: Int): PingOutcome? {
        if (probes >= maxProbes || !hasTime()) return null
        probes++
        return probe(size)
    }

    var top = test(tunnelMtu) ?: return MtuSearch.Inconclusive
    if (top == PingOutcome.UNAVAILABLE) return MtuSearch.Unavailable
    if (top == PingOutcome.REPLY) return MtuSearch.Full(tunnelMtu)
    top = test(tunnelMtu) ?: return MtuSearch.Inconclusive
    if (top == PingOutcome.UNAVAILABLE) return MtuSearch.Unavailable
    if (top == PingOutcome.REPLY) return MtuSearch.Full(tunnelMtu)
    if (tunnelMtu <= minMtu) return MtuSearch.NoAnswer

    when (test(minMtu) ?: return MtuSearch.Inconclusive) {
        PingOutcome.UNAVAILABLE -> return MtuSearch.Unavailable
        PingOutcome.NO_REPLY -> return MtuSearch.NoAnswer
        PingOutcome.REPLY -> Unit
    }
    var lo = minMtu // known to pass
    var hi = tunnelMtu // known to fail
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        when (test(mid) ?: break) {
            PingOutcome.REPLY -> lo = mid
            PingOutcome.NO_REPLY -> hi = mid
            PingOutcome.UNAVAILABLE -> break
        }
    }
    return MtuSearch.Reduced(lo, tunnelMtu)
}

fun evaluateMtu(search: MtuSearch): CheckResult = when (search) {
    is MtuSearch.Full -> CheckResult(CheckId.MTU, CheckStatus.OK, DiagText(R.string.diag_mtu_ok, search.mtu), "${search.mtu}")
    is MtuSearch.Reduced -> CheckResult(
        CheckId.MTU, CheckStatus.WARN,
        DiagText(R.string.diag_mtu_reduced, search.largest, search.mtu),
        "${search.largest} of ${search.mtu}",
        DiagText(R.string.diag_mtu_reduced_hint, search.largest),
    )
    MtuSearch.NoAnswer -> CheckResult(
        CheckId.MTU, CheckStatus.SKIPPED, DiagText(R.string.diag_mtu_no_answer), "no icmp reply",
        DiagText(R.string.diag_mtu_no_answer_hint),
    )
    MtuSearch.Unavailable -> CheckResult(
        CheckId.MTU, CheckStatus.SKIPPED, DiagText(R.string.diag_mtu_no_ping), "ping unavailable",
        DiagText(R.string.diag_mtu_no_ping_hint),
    )
    MtuSearch.Inconclusive -> CheckResult(
        CheckId.MTU, CheckStatus.SKIPPED, DiagText(R.string.diag_mtu_inconclusive), "timeout",
        DiagText(R.string.diag_timeout_hint),
    )
}

fun mtuNoGateway(): CheckResult = CheckResult(
    CheckId.MTU, CheckStatus.SKIPPED, DiagText(R.string.diag_mtu_no_gateway), null, DiagText(R.string.diag_mtu_no_gateway_hint),
)

private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

fun isIpv4(s: String?): Boolean = s != null && IPV4.matches(s.trim())

// ─── 7. speed ────────────────────────────────────────────────────────────

const val SPEED_DOWNLOAD_MAX_BYTES = 25L * 1024 * 1024
const val SPEED_UPLOAD_MAX_BYTES = 10L * 1024 * 1024
const val SPEED_TIME_CAP_MS = 8_000L
const val SPEED_SLOW_MBPS = 1.0
private const val SERVER_MS_PLAUSIBLE_MAX = 120_000L

/** Mbit/s from bytes and milliseconds; null without a usable measurement. */
fun mbps(bytes: Long, millis: Long): Double? =
    if (bytes <= 0 || millis <= 0) null else bytes * 8.0 / (millis * 1000.0)

/** The client's own time; the server's only when the client has none and it is plausible. */
fun uploadMillis(clientMs: Long?, serverMs: Long?): Long? = when {
    clientMs != null && clientMs > 0 -> clientMs
    serverMs != null && serverMs in 1..SERVER_MS_PLAUSIBLE_MAX -> serverMs
    else -> null
}

/** Bytes that arrived: the server's count when it is plausible, else what was written. */
fun uploadBytes(written: Long, serverBytes: Long?): Long =
    if (serverBytes != null && serverBytes in 1..written) serverBytes else written

enum class SpeedSkip { UNSUPPORTED, RATE_LIMITED, NO_SERVER }

fun speedSkipped(reason: SpeedSkip): CheckResult = when (reason) {
    SpeedSkip.UNSUPPORTED -> CheckResult(
        CheckId.SPEED, CheckStatus.SKIPPED, DiagText(R.string.diag_speed_unsupported), "unsupported (404)",
        DiagText(R.string.diag_speed_unsupported_hint),
    )
    SpeedSkip.RATE_LIMITED -> CheckResult(
        CheckId.SPEED, CheckStatus.SKIPPED, DiagText(R.string.diag_speed_rate_limited), "rate limited (429)",
        DiagText(R.string.diag_speed_rate_limited_hint),
    )
    SpeedSkip.NO_SERVER -> CheckResult(
        CheckId.SPEED, CheckStatus.SKIPPED, DiagText(R.string.diag_skipped), null,
        DiagText(R.string.diag_speed_no_server_hint),
    )
}

/**
 * Informational: ok unless both directions stay below 1 Mbit/s (a failed
 * direction counts as 0); warn as well when neither could be measured.
 */
fun evaluateSpeed(downMbps: Double?, upMbps: Double?, viaTunnel: Boolean?): CheckResult {
    if (downMbps == null && upMbps == null) {
        return CheckResult(CheckId.SPEED, CheckStatus.WARN, DiagText(R.string.diag_speed_failed), "failed", DiagText(R.string.diag_speed_failed_hint))
    }
    fun rate(v: Double?) = if (v == null) DiagText(R.string.diag_speed_none) else DiagText(R.string.diag_speed_mbps, Mbps(v))
    val rates = DiagText(R.string.diag_speed_value, rate(downMbps), rate(upMbps))
    val detail = when (viaTunnel) {
        true -> DiagText(R.string.diag_speed_via_tunnel, rates)
        false -> DiagText(R.string.diag_speed_direct, rates)
        null -> rates
    }
    val value = "down ${downMbps?.let { Mbps(it).plain() } ?: "-"} / up ${upMbps?.let { Mbps(it).plain() } ?: "-"} Mbit/s" +
        when (viaTunnel) { true -> ", via tunnel"; false -> ", direct"; null -> "" }
    val slow = (downMbps ?: 0.0) < SPEED_SLOW_MBPS && (upMbps ?: 0.0) < SPEED_SLOW_MBPS
    return if (slow) {
        CheckResult(CheckId.SPEED, CheckStatus.WARN, detail, value, DiagText(R.string.diag_speed_slow_hint))
    } else {
        CheckResult(CheckId.SPEED, CheckStatus.OK, detail, value)
    }
}

// ─── generic ─────────────────────────────────────────────────────────────

/** A check that ran out of time or threw: never aborts the others. */
fun checkTimedOut(id: CheckId): CheckResult = CheckResult(
    id,
    when (id) {
        CheckId.INTERNET, CheckId.SERVER, CheckId.TUNNEL -> CheckStatus.FAIL
        CheckId.MTU -> CheckStatus.SKIPPED
        else -> CheckStatus.WARN
    },
    DiagText(R.string.diag_timeout), "timeout", DiagText(R.string.diag_timeout_hint),
)

fun checkCrashed(id: CheckId, t: Throwable): CheckResult = CheckResult(
    id,
    if (id == CheckId.MTU) CheckStatus.SKIPPED else CheckStatus.WARN,
    DiagText(R.string.diag_error, t.javaClass.simpleName), t.javaClass.simpleName, DiagText(R.string.diag_timeout_hint),
)
