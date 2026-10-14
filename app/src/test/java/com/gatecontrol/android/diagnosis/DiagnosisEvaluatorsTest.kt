package com.gatecontrol.android.diagnosis

import com.gatecontrol.android.R
import com.gatecontrol.android.tunnel.TunnelState
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException

class DiagnosisEvaluatorsTest {

    private fun r(id: CheckId, status: CheckStatus, hint: Int? = null) =
        CheckResult(id, status, hint = hint?.let { DiagText(it) })

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))

    // ─── summary ─────────────────────────────────────────────────────────

    @Test
    fun `summary - fail beats warn beats ok, skipped never degrades`() {
        val ok = CheckId.entries.map { r(it, CheckStatus.OK) }
        assertEquals(DiagnosisSummary.OK, DiagnosisReport.summarize(ok))

        val skipped = ok.map { if (it.id == CheckId.SPEED || it.id == CheckId.MTU) it.copy(status = CheckStatus.SKIPPED) else it }
        val report = DiagnosisReport(0, 1, skipped)
        assertEquals(DiagnosisSummary.OK, report.summary)
        assertEquals(2, report.skippedCount)
        assertNull(report.topIssue)

        val warn = skipped.map { if (it.id == CheckId.DNS) it.copy(status = CheckStatus.WARN) else it }
        assertEquals(DiagnosisSummary.WARN, DiagnosisReport.summarize(warn))
        assertEquals(CheckId.DNS, DiagnosisReport.mostImportant(warn)?.id)

        val fail = warn.map { if (it.id == CheckId.HANDSHAKE) it.copy(status = CheckStatus.FAIL) else it }
        val failReport = DiagnosisReport(0, 1, fail)
        assertEquals(DiagnosisSummary.FAIL, failReport.summary)
        // The failure is the most important hint even though the warning comes first or later.
        assertEquals(CheckId.HANDSHAKE, failReport.topIssue?.id)
        assertEquals(2, failReport.issueCount)
    }

    // ─── internet ────────────────────────────────────────────────────────

    @Test
    fun `internet - validated network names the transport, none or unvalidated fails`() {
        val ok = evaluateInternet(InternetState(connected = true, validated = true, transport = Transport.WIFI))
        assertEquals(CheckStatus.OK, ok.status)
        assertEquals(DiagText(R.string.diag_internet_ok, DiagText(R.string.diag_transport_wifi)), ok.detail)
        assertEquals("wifi", ok.value)

        assertEquals(CheckStatus.FAIL, evaluateInternet(null).status)
        assertEquals(CheckStatus.FAIL, evaluateInternet(InternetState(false, false, Transport.OTHER)).status)
        val portal = evaluateInternet(InternetState(true, false, Transport.CELLULAR))
        assertEquals(CheckStatus.FAIL, portal.status)
        assertEquals(DiagText(R.string.diag_internet_not_validated_hint), portal.hint)
    }

    // ─── server ──────────────────────────────────────────────────────────

    @Test
    fun `latency - ok below 150, warn below 600, slow warn above`() {
        assertEquals(CheckStatus.OK, classifyLatency(38).status)
        assertEquals(DiagText(R.string.diag_server_ms, 38L), classifyLatency(38).detail)
        assertEquals("38 ms", classifyLatency(38).value)
        assertEquals(CheckStatus.OK, classifyLatency(149).status)
        assertEquals(CheckStatus.WARN, classifyLatency(150).status)
        assertEquals(DiagText(R.string.diag_server_ms_elevated, 599L), classifyLatency(599).detail)
        assertEquals(CheckStatus.WARN, classifyLatency(600).status)
        assertEquals(DiagText(R.string.diag_server_ms_slow, 1200L), classifyLatency(1200).detail)
    }

    @Test
    fun `server - median of the answers`() {
        assertEquals(40L, median(listOf(300, 40, 20)))
        assertEquals(30L, median(listOf(20, 40)))
        assertNull(median(emptyList()))
        val res = evaluateServer(listOf(900, 40, 45), null)
        assertEquals(CheckStatus.OK, res.status)
        assertEquals("45 ms", res.value)
    }

    @Test
    fun `server - errors in plain words`() {
        assertEquals(ServerErrorKind.DNS, classifyError(UnknownHostException("gate.example.com")))
        assertEquals(ServerErrorKind.TIMEOUT, classifyError(SocketTimeoutException()))
        assertEquals(ServerErrorKind.TLS, classifyError(SSLHandshakeException("bad cert")))
        assertEquals(ServerErrorKind.CONNECT, classifyError(ConnectException()))
        assertEquals(ServerErrorKind.AUTH, classifyError(http(401)))
        assertEquals(ServerErrorKind.AUTH, classifyError(http(403)))
        assertEquals(ServerErrorKind.HTTP, classifyError(http(502)))
        assertEquals(ServerErrorKind.DNS, classifyError(RuntimeException(UnknownHostException())))
        assertEquals(ServerErrorKind.OTHER, classifyError(IllegalStateException()))

        val auth = evaluateServer(emptyList(), http(401))
        assertEquals(CheckStatus.FAIL, auth.status)
        assertEquals(DiagText(R.string.diag_server_err_auth, 401), auth.detail)
        assertEquals(DiagText(R.string.diag_server_err_auth_hint), auth.hint)
        assertEquals("HTTP 401", auth.value)

        val dns = evaluateServer(emptyList(), UnknownHostException())
        assertEquals(DiagText(R.string.diag_server_err_dns), dns.detail)
    }

    // ─── tunnel ──────────────────────────────────────────────────────────

    @Test
    fun `tunnel - connected ok, off warns, error fails`() {
        val ok = evaluateTunnel(TunnelState.Connected(connectedSince = 1_000_000), 1_000_000 + 12 * 60_000)
        assertEquals(CheckStatus.OK, ok.status)
        assertEquals(DiagText(R.string.diag_tunnel_connected_since, "12m"), ok.detail)
        assertEquals(CheckStatus.SKIPPED, evaluateTunnel(TunnelState.Disconnected, 0).status)
        assertEquals(CheckStatus.WARN, evaluateTunnel(TunnelState.Reconnecting(1, 3), 0).status)
        assertEquals(CheckStatus.FAIL, evaluateTunnel(TunnelState.Error("no handshake"), 0).status)
        assertEquals(CheckStatus.SKIPPED, skippedNoTunnel(CheckId.DNS).status)
        assertEquals(DiagText(R.string.diag_skipped_no_tunnel_hint), skippedNoTunnel(CheckId.DNS).hint)
    }

    // ─── handshake ───────────────────────────────────────────────────────

    private val now = 1_790_000_000_000L
    private fun sample(ageSec: Long?, rx: Long = 1000, at: Long = now) =
        HandshakeSample(if (ageSec == null) 0 else at / 1000 - ageSec, rx, at)

    @Test
    fun `handshake - fresh is ok without recheck`() {
        val s = sample(42)
        assertEquals(42L, s.ageSec)
        assertTrue(!s.needsRecheck)
        val res = evaluateHandshake(s, null)
        assertEquals(CheckStatus.OK, res.status)
        assertEquals(DiagText(R.string.diag_handshake_ago_s, 42L), res.detail)
        assertNull(res.hint)
    }

    @Test
    fun `handshake - stale first, fresh after traffic is ok`() {
        val first = sample(400)
        assertTrue(first.needsRecheck)
        val second = sample(1, rx = 1300, at = now + 3000)
        val res = evaluateHandshake(first, second)
        assertEquals(CheckStatus.OK, res.status)
        assertEquals("1s, rechecked", res.value)
    }

    @Test
    fun `handshake - still stale after traffic warns or fails, notes missing rx`() {
        val warn = evaluateHandshake(sample(300), sample(303, rx = 1000, at = now + 3000))
        assertEquals(CheckStatus.WARN, warn.status)
        assertEquals(DiagText(R.string.diag_handshake_ago_min, 5L), warn.detail)
        assertEquals(DiagText(R.string.diag_handshake_hint_no_rx), warn.hint)

        val fail = evaluateHandshake(sample(900), sample(903, rx = 1500, at = now + 3000))
        assertEquals(CheckStatus.FAIL, fail.status)
        assertEquals(DiagText(R.string.diag_handshake_hint), fail.hint)

        val never = evaluateHandshake(sample(null), sample(null, at = now + 3000))
        assertEquals(CheckStatus.FAIL, never.status)
        assertEquals(DiagText(R.string.diag_handshake_never), never.detail)
        assertTrue(never.value!!.contains("rx unchanged"))
    }

    @Test
    fun `handshake - limits are inclusive`() {
        assertEquals(CheckStatus.OK, evaluateHandshake(sample(180), null).status)
        assertEquals(CheckStatus.WARN, evaluateHandshake(sample(600), sample(600, rx = 2000)).status)
        assertEquals(CheckStatus.FAIL, evaluateHandshake(sample(601), sample(601, rx = 2000)).status)
    }

    // ─── dns ─────────────────────────────────────────────────────────────

    @Test
    fun `dns - parses the server list`() {
        assertEquals(listOf("10.8.0.1", "10.8.0.2"), parseDnsList("10.8.0.1, 10.8.0.2,10.8.0.1"))
        assertEquals(emptyList<String>(), parseDnsList(null))
        assertEquals("fe80::1", normalizeIp("/FE80::1%wlan0"))
    }

    @Test
    fun `dns - same resolvers as the server is ok`() {
        val res = evaluateDns(VpnDnsState(true, listOf("10.8.0.1")), listOf("10.8.0.1"))
        assertEquals(CheckStatus.OK, res.status)
        assertEquals(DiagText(R.string.diag_dns_ok, "10.8.0.1"), res.detail)
    }

    @Test
    fun `dns - foreign resolver fails, extra resolver warns, none fails`() {
        val leak = evaluateDns(VpnDnsState(true, listOf("8.8.8.8")), listOf("10.8.0.1"))
        assertEquals(CheckStatus.FAIL, leak.status)
        assertEquals(DiagText(R.string.diag_dns_mismatch, "8.8.8.8"), leak.detail)

        val extra = evaluateDns(VpnDnsState(true, listOf("10.8.0.1", "1.1.1.1")), listOf("10.8.0.1"))
        assertEquals(CheckStatus.WARN, extra.status)
        assertEquals(DiagText(R.string.diag_dns_partial, "1.1.1.1"), extra.detail)

        assertEquals(CheckStatus.FAIL, evaluateDns(VpnDnsState(true, emptyList()), listOf("10.8.0.1")).status)
        assertEquals(CheckStatus.WARN, evaluateDns(VpnDnsState(false, emptyList()), listOf("10.8.0.1")).status)
        assertEquals(CheckStatus.WARN, evaluateDns(VpnDnsState(true, listOf("10.8.0.1")), null).status)
    }

    @Test
    fun `dns - strict private DNS warns, automatic mode does not`() {
        val strict = evaluateDns(
            VpnDnsState(true, listOf("10.8.0.1"), privateDnsActive = true, privateDnsServerName = "dns.google"),
            listOf("10.8.0.1"),
        )
        assertEquals(CheckStatus.WARN, strict.status)
        assertEquals(DiagText(R.string.diag_dns_private, "dns.google"), strict.detail)
        assertEquals(DiagText(R.string.diag_dns_private_hint, "dns.google"), strict.hint)

        val automatic = evaluateDns(VpnDnsState(true, listOf("10.8.0.1"), privateDnsActive = true, privateDnsServerName = null), listOf("10.8.0.1"))
        assertEquals(CheckStatus.OK, automatic.status)

        // A leak stays a failure even with private DNS on.
        val leak = evaluateDns(VpnDnsState(true, listOf("8.8.8.8"), true, "dns.google"), listOf("10.8.0.1"))
        assertEquals(CheckStatus.FAIL, leak.status)
    }

    // ─── mtu ─────────────────────────────────────────────────────────────

    /** Fake path: everything up to [pathMtu] answers; records the probed sizes. */
    private class FakePath(val pathMtu: Int, val outcome: PingOutcome? = null) {
        val probed = mutableListOf<Int>()
        suspend fun probe(size: Int): PingOutcome {
            probed += size
            return outcome ?: if (size <= pathMtu) PingOutcome.REPLY else PingOutcome.NO_REPLY
        }
    }

    @Test
    fun `mtu - full tunnel MTU passes with one probe`() = runTest {
        val path = FakePath(1500)
        assertEquals(MtuSearch.Full(1420), searchMtu(1420, probe = path::probe))
        assertEquals(listOf(1420), path.probed)
    }

    @Test
    fun `mtu - binary search finds the largest size within 8 probes`() = runTest {
        for (pathMtu in listOf(1281, 1300, 1380, 1399, 1419)) {
            val path = FakePath(pathMtu)
            val res = searchMtu(1420, probe = path::probe) as MtuSearch.Reduced
            assertTrue(path.probed.size <= MTU_MAX_PROBES, "probes ${path.probed}")
            assertTrue(res.largest <= pathMtu, "never above the real path MTU")
            assertTrue(pathMtu - res.largest <= 5, "close to the real path MTU: ${res.largest} vs $pathMtu")
            assertEquals(1420, res.mtu)
        }
    }

    @Test
    fun `mtu - exact with enough probes`() = runTest {
        val path = FakePath(1380)
        assertEquals(MtuSearch.Reduced(1380, 1420), searchMtu(1420, maxProbes = 20, probe = path::probe))
    }

    @Test
    fun `mtu - no answer even at 1280, unavailable ping, out of time`() = runTest {
        assertEquals(MtuSearch.NoAnswer, searchMtu(1420, probe = FakePath(0, PingOutcome.NO_REPLY)::probe))
        assertEquals(MtuSearch.Unavailable, searchMtu(1420, probe = FakePath(0, PingOutcome.UNAVAILABLE)::probe))
        assertEquals(MtuSearch.Inconclusive, searchMtu(1420, hasTime = { false }, probe = FakePath(1500)::probe))
        // Time runs out mid-search: best known size so far.
        var calls = 0
        val res = searchMtu(1420, hasTime = { calls < 4 }) { size -> calls++; if (size <= 1350) PingOutcome.REPLY else PingOutcome.NO_REPLY }
        assertTrue(res is MtuSearch.Reduced && res.largest in 1280..1350)
    }

    @Test
    fun `mtu - a single lost reply at the full size is retried`() = runTest {
        var first = true
        val res = searchMtu(1420) { _ -> if (first) { first = false; PingOutcome.NO_REPLY } else PingOutcome.REPLY }
        assertEquals(MtuSearch.Full(1420), res)
    }

    @Test
    fun `mtu - evaluation`() {
        assertEquals(CheckStatus.OK, evaluateMtu(MtuSearch.Full(1420)).status)
        assertEquals(DiagText(R.string.diag_mtu_ok, 1420), evaluateMtu(MtuSearch.Full(1420)).detail)
        val reduced = evaluateMtu(MtuSearch.Reduced(1380, 1420))
        assertEquals(CheckStatus.WARN, reduced.status)
        assertEquals(DiagText(R.string.diag_mtu_reduced_hint, 1380), reduced.hint)
        assertEquals(CheckStatus.SKIPPED, evaluateMtu(MtuSearch.NoAnswer).status)
        assertEquals(CheckStatus.SKIPPED, evaluateMtu(MtuSearch.Unavailable).status)
    }

    @Test
    fun `mtu - ping exit codes`() {
        assertEquals(PingOutcome.REPLY, parsePing(CommandResult(0, "1 packets transmitted, 1 received")))
        assertEquals(PingOutcome.NO_REPLY, parsePing(CommandResult(1, "1 packets transmitted, 0 received")))
        assertEquals(PingOutcome.NO_REPLY, parsePing(CommandResult(null, "")))
        assertEquals(PingOutcome.NO_REPLY, parsePing(CommandResult(2, "ping: local error: message too long, mtu=1420")))
        assertEquals(PingOutcome.UNAVAILABLE, parsePing(CommandResult(2, "ping: socket: Operation not permitted")))
        assertEquals(PingOutcome.UNAVAILABLE, parsePing(CommandResult(null, "", started = false)))
        assertTrue(isIpv4("10.8.0.1"))
        assertTrue(!isIpv4("fd00::1"))
        assertTrue(!isIpv4("10.8.0.256"))
    }

    // ─── speed ───────────────────────────────────────────────────────────

    @Test
    fun `speed - Mbit per second from bytes and time`() {
        // 25 MiB in 2 s = 104.9 Mbit/s
        assertEquals(104.857_6, mbps(25L * 1024 * 1024, 2000)!!, 0.001)
        assertEquals(8.0, mbps(1_000_000, 1000)!!, 1e-9)
        assertNull(mbps(0, 1000))
        assertNull(mbps(1000, 0))
    }

    @Test
    fun `speed - client time first, server time only as plausible fallback`() {
        assertEquals(1500L, uploadMillis(1500, 900))
        assertEquals(900L, uploadMillis(null, 900))
        assertEquals(900L, uploadMillis(0, 900))
        assertNull(uploadMillis(null, 0))
        assertNull(uploadMillis(null, 10_000_000))
        assertEquals(500L, uploadBytes(1000, 500))
        assertEquals(1000L, uploadBytes(1000, 5000))
        assertEquals(1000L, uploadBytes(1000, null))
    }

    @Test
    fun `speed - informational ok, warn when both directions below 1 Mbit`() {
        val ok = evaluateSpeed(87.2, 31.0, viaTunnel = true)
        assertEquals(CheckStatus.OK, ok.status)
        val rates = DiagText(
            R.string.diag_speed_value,
            DiagText(R.string.diag_speed_mbps, Mbps(87.2)),
            DiagText(R.string.diag_speed_mbps, Mbps(31.0)),
        )
        assertEquals(DiagText(R.string.diag_speed_via_tunnel, rates), ok.detail)
        assertEquals("down 87.2 / up 31.0 Mbit/s, via tunnel", ok.value)

        assertEquals(CheckStatus.OK, evaluateSpeed(0.5, 2.0, false).status)
        assertEquals(CheckStatus.WARN, evaluateSpeed(0.5, 0.4, false).status)
        assertEquals(CheckStatus.WARN, evaluateSpeed(0.5, null, null).status)
        assertEquals(DiagText(R.string.diag_speed_failed), evaluateSpeed(null, null, null).detail)
    }

    @Test
    fun `speed - skipped reasons`() {
        assertEquals(CheckStatus.SKIPPED, speedSkipped(SpeedSkip.UNSUPPORTED).status)
        assertEquals(DiagText(R.string.diag_speed_unsupported), speedSkipped(SpeedSkip.UNSUPPORTED).detail)
        assertEquals(DiagText(R.string.diag_speed_rate_limited_hint), speedSkipped(SpeedSkip.RATE_LIMITED).hint)
    }

    @Test
    fun `mbps formatting follows the locale`() {
        assertEquals("87", Mbps(87.2).format(Locale.GERMANY))
        assertEquals("4,2", Mbps(4.2).format(Locale.GERMANY))
        assertEquals("4.2", Mbps(4.2).format(Locale.US))
        assertEquals("4.2", Mbps(4.2).plain())
    }
}
