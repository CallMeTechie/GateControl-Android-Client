package com.gatecontrol.android.diagnosis

import com.gatecontrol.android.R
import com.gatecontrol.android.network.DnsCheckResponse
import com.gatecontrol.android.network.SpeedTestAvailability
import com.gatecontrol.android.network.SpeedTestHttpException
import com.gatecontrol.android.network.SpeedTransfer
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.UnknownHostException

class DiagnosisRunnerTest {

    private val epoch = 1_790_000_000_000L

    private class FakeNetwork(
        var internet: InternetState? = InternetState(true, true, Transport.WIFI),
        var dns: VpnDnsState = VpnDnsState(true, listOf("10.8.0.1")),
    ) : NetworkProbe {
        override fun internet() = internet
        override fun vpnDns() = dns
    }

    private class FakeServer(
        var configured: Boolean = true,
        val pings: ArrayDeque<() -> Long> = ArrayDeque(),
        var pingFallback: () -> Long = { 40L },
        var dnsCheck: () -> DnsCheckResponse = { DnsCheckResponse(true, "10.8.0.0/24", "10.8.0.1", "10.8.0.1") },
        var onPing: () -> Unit = {},
    ) : ServerProbe {
        var pingCount = 0
        override fun isConfigured() = configured
        override suspend fun ping(): Long {
            pingCount++
            onPing()
            return (pings.removeFirstOrNull() ?: pingFallback)()
        }
        override suspend fun dnsCheck() = dnsCheck.invoke()
    }

    private inner class FakeTunnel(
        var state: TunnelState = TunnelState.Connected(connectedSince = epoch - 600_000),
        var stats: TunnelStats? = TunnelStats(rxBytes = 1000, lastHandshakeEpoch = epoch / 1000 - 30),
        var mtu: Int = 1420,
    ) : TunnelProbe {
        override fun state() = state
        override fun stats() = stats
        override fun tunnelMtu() = mtu
    }

    private class FakeCommands(val pathMtu: Int = 1500, val result: CommandResult? = null) : CommandRunner {
        val sizes = mutableListOf<Int>()
        override suspend fun run(command: List<String>, timeoutMs: Long): CommandResult {
            val payload = command[command.indexOf("-s") + 1].toInt()
            sizes += payload + ICMP_IPV4_OVERHEAD
            return result ?: CommandResult(if (payload + ICMP_IPV4_OVERHEAD <= pathMtu) 0 else 1, "")
        }
    }

    private class FakeSpeed(
        var info: suspend () -> SpeedTestAvailability = { SpeedTestAvailability.Available(50L shl 20, 20L shl 20, true) },
        var down: suspend (Long) -> SpeedTransfer = { SpeedTransfer(it, 2000) },
        var up: suspend (Long) -> SpeedTransfer = { SpeedTransfer(it, 4000, serverMs = 3900, serverBytes = it) },
    ) : SpeedProbe {
        val downRequested = mutableListOf<Long>()
        val upRequested = mutableListOf<Long>()
        override suspend fun info() = info.invoke()
        override suspend fun download(bytes: Long, maxMillis: Long): SpeedTransfer { downRequested += bytes; return down(bytes) }
        override suspend fun upload(bytes: Long, maxMillis: Long): SpeedTransfer { upRequested += bytes; return up(bytes) }
    }

    private fun TestScope.runner(
        network: FakeNetwork = FakeNetwork(),
        server: FakeServer = FakeServer(),
        tunnel: FakeTunnel = FakeTunnel(),
        commands: FakeCommands = FakeCommands(),
        speed: FakeSpeed = FakeSpeed(),
    ) = DiagnosisRunner(network, server, tunnel, commands, speed) { epoch + testScheduler.currentTime }

    private fun DiagnosisReport.of(id: CheckId) = checks.first { it.id == id }

    @Test
    fun `all fine - every check ok, order and progress`() = runTest {
        val updates = mutableListOf<List<CheckResult>>()
        val report = runner().run { updates += it }

        assertEquals(CheckId.entries, report.checks.map { it.id })
        assertTrue(report.checks.all { it.status == CheckStatus.OK }, report.checks.toString())
        assertEquals(DiagnosisSummary.OK, report.summary)
        assertEquals(DiagText(R.string.diag_server_ms, 40L), report.of(CheckId.SERVER).detail)
        // pending → running → done for each check, plus the initial list
        assertEquals(1 + 2 * CheckId.entries.size, updates.size)
        assertEquals(CheckStatus.RUNNING, updates[1][0].status)
        assertEquals(CheckStatus.PENDING, updates[1][1].status)
    }

    @Test
    fun `tunnel down - tunnel checks skipped, speed still runs`() = runTest {
        val report = runner(tunnel = FakeTunnel(state = TunnelState.Disconnected)).run()
        assertEquals(CheckStatus.WARN, report.of(CheckId.TUNNEL).status)
        for (id in listOf(CheckId.HANDSHAKE, CheckId.DNS, CheckId.MTU)) {
            assertEquals(CheckStatus.SKIPPED, report.of(id).status)
            assertEquals(DiagText(R.string.diag_skipped_no_tunnel_hint), report.of(id).hint)
        }
        assertEquals(CheckStatus.OK, report.of(CheckId.SPEED).status)
        assertEquals(DiagnosisSummary.WARN, report.summary)
        assertEquals(3, report.skippedCount)
    }

    @Test
    fun `server unreachable - fails with the error class, speed skipped, others still run`() = runTest {
        val server = FakeServer(pingFallback = { throw UnknownHostException("gate.example.com") })
        val report = runner(server = server).run()
        val s = report.of(CheckId.SERVER)
        assertEquals(CheckStatus.FAIL, s.status)
        assertEquals(DiagText(R.string.diag_server_err_dns), s.detail)
        // Stops after the first error instead of waiting three times.
        assertTrue(server.pingCount <= 2, "pings: ${server.pingCount}")
        assertEquals(CheckStatus.SKIPPED, report.of(CheckId.SPEED).status)
        assertEquals(CheckStatus.OK, report.of(CheckId.TUNNEL).status)
        assertEquals(DiagnosisSummary.FAIL, report.summary)
    }

    @Test
    fun `server - median of three pings`() = runTest {
        val server = FakeServer(pings = ArrayDeque(listOf({ 700L }, { 120L }, { 130L })))
        val report = runner(server = server).run()
        assertEquals("130 ms", report.of(CheckId.SERVER).value)
        assertEquals(CheckStatus.OK, report.of(CheckId.SERVER).status)
    }

    @Test
    fun `a crashing check does not abort the others`() = runTest {
        val network = object : NetworkProbe {
            override fun internet(): InternetState = throw IllegalStateException("boom")
            override fun vpnDns() = VpnDnsState(true, listOf("10.8.0.1"))
        }
        val report = DiagnosisRunner(network, FakeServer(), FakeTunnel(), FakeCommands(), FakeSpeed()) { epoch + testScheduler.currentTime }.run()
        assertEquals(CheckStatus.WARN, report.of(CheckId.INTERNET).status)
        assertEquals(CheckStatus.OK, report.of(CheckId.SERVER).status)
        assertEquals(CheckStatus.OK, report.of(CheckId.SPEED).status)
    }

    @Test
    fun `a hanging check times out and the run goes on`() = runTest {
        val server = FakeServer(dnsCheck = { throw UnknownHostException() })
        val speed = FakeSpeed(info = { delay(10 * 60_000); SpeedTestAvailability.Unsupported })
        val report = runner(server = server, speed = speed).run()
        assertEquals(DiagText(R.string.diag_timeout), report.of(CheckId.SPEED).detail)
        assertEquals(CheckStatus.WARN, report.of(CheckId.SPEED).status)
        // dns-check failed: DNS cannot be judged, MTU has no gateway
        assertEquals(DiagText(R.string.diag_dns_unknown), report.of(CheckId.DNS).detail)
        assertEquals(DiagText(R.string.diag_mtu_no_gateway), report.of(CheckId.MTU).detail)
    }

    // ─── handshake re-check path ─────────────────────────────────────────

    @Test
    fun `handshake - stale handshake is re-read after generating traffic`() = runTest {
        val tunnel = FakeTunnel(stats = TunnelStats(rxBytes = 1000, lastHandshakeEpoch = epoch / 1000 - 400))
        val server = FakeServer()
        val r = runner(server = server, tunnel = tunnel)
        // Traffic through the tunnel leads to a new handshake.
        server.onPing = {
            if (server.pingCount > 3) tunnel.stats = TunnelStats(rxBytes = 1400, lastHandshakeEpoch = (epoch + testScheduler.currentTime) / 1000)
        }
        val report = r.run()
        val hs = report.of(CheckId.HANDSHAKE)
        assertEquals(CheckStatus.OK, hs.status, hs.toString())
        assertTrue(hs.value!!.contains("rechecked"))
        assertEquals(4, server.pingCount, "3 latency pings + 1 traffic ping")
        assertTrue(testScheduler.currentTime >= DiagnosisRunner.HANDSHAKE_RECHECK_DELAY_MS)
    }

    @Test
    fun `handshake - no answer after traffic fails and notes missing rx`() = runTest {
        val tunnel = FakeTunnel(stats = TunnelStats(rxBytes = 1000, lastHandshakeEpoch = epoch / 1000 - 900))
        val report = runner(tunnel = tunnel).run()
        val hs = report.of(CheckId.HANDSHAKE)
        assertEquals(CheckStatus.FAIL, hs.status)
        assertEquals(DiagText(R.string.diag_handshake_hint_no_rx), hs.hint)
    }

    // ─── dns ─────────────────────────────────────────────────────────────

    @Test
    fun `dns - compares the VPN network with the server's vpnDns`() = runTest {
        val network = FakeNetwork(dns = VpnDnsState(true, listOf("8.8.8.8")))
        assertEquals(CheckStatus.FAIL, runner(network = network).run().of(CheckId.DNS).status)

        network.dns = VpnDnsState(true, listOf("10.8.0.1"), privateDnsActive = true, privateDnsServerName = "one.one.one.one")
        val strict = runner(network = network).run().of(CheckId.DNS)
        assertEquals(CheckStatus.WARN, strict.status)
        assertEquals(DiagText(R.string.diag_dns_private, "one.one.one.one"), strict.detail)
    }

    // ─── mtu ─────────────────────────────────────────────────────────────

    @Test
    fun `mtu - probes the gateway with DF pings and reports the largest size`() = runTest {
        val commands = FakeCommands(pathMtu = 1380)
        val mtu = runner(commands = commands).run().of(CheckId.MTU)
        assertEquals(CheckStatus.WARN, mtu.status)
        assertTrue(commands.sizes.size <= MTU_MAX_PROBES)
        assertEquals(1420, commands.sizes.first())
        val largest = (mtu.hint!!.args.first() as Int)
        assertTrue(largest in 1376..1380, "largest $largest")
    }

    @Test
    fun `mtu - command line and unavailable ping`() = runTest {
        val seen = mutableListOf<List<String>>()
        val commands = object : CommandRunner {
            override suspend fun run(command: List<String>, timeoutMs: Long): CommandResult {
                seen += command
                return CommandResult(null, "", started = false)
            }
        }
        val report = DiagnosisRunner(FakeNetwork(), FakeServer(), FakeTunnel(), commands, FakeSpeed()) { epoch + testScheduler.currentTime }.run()
        assertEquals(listOf("/system/bin/ping", "-M", "do", "-c", "1", "-W", "1", "-s", "1392", "10.8.0.1"), seen.first())
        assertEquals(CheckStatus.SKIPPED, report.of(CheckId.MTU).status)
        assertEquals(DiagText(R.string.diag_mtu_no_ping), report.of(CheckId.MTU).detail)
        assertEquals(DiagnosisSummary.OK, report.summary)
    }

    // ─── speed ───────────────────────────────────────────────────────────

    @Test
    fun `speed - sizes capped by the server and the app, rates from measured bytes`() = runTest {
        val speed = FakeSpeed(
            info = { SpeedTestAvailability.Available(maxDownloadBytes = 8L shl 20, maxUploadBytes = 100L shl 20, viaTunnel = false) },
            // Time cap hit: only 4 MiB of 8 MiB arrived in 8 s
            down = { SpeedTransfer(4L shl 20, 8000) },
            up = { SpeedTransfer(it, 2000, serverMs = 1, serverBytes = it) },
        )
        val res = runner(speed = speed).run().of(CheckId.SPEED)
        assertEquals(listOf(8L shl 20), speed.downRequested)
        assertEquals(listOf(SPEED_UPLOAD_MAX_BYTES), speed.upRequested)
        assertEquals(CheckStatus.OK, res.status)
        // 4 MiB / 8 s = 4.2 Mbit/s; 10 MiB / 2 s = 41.9 Mbit/s (client time, not serverMs)
        assertEquals("down 4.2 / up 41.9 Mbit/s, direct", res.value)
    }

    @Test
    fun `speed - 404 skipped as unsupported, 429 skipped as rate limited`() = runTest {
        val unsupported = runner(speed = FakeSpeed(info = { SpeedTestAvailability.Unsupported })).run().of(CheckId.SPEED)
        assertEquals(CheckStatus.SKIPPED, unsupported.status)
        assertEquals(DiagText(R.string.diag_speed_unsupported), unsupported.detail)

        val limited = runner(speed = FakeSpeed(info = { SpeedTestAvailability.RateLimited })).run().of(CheckId.SPEED)
        assertEquals(DiagText(R.string.diag_speed_rate_limited), limited.detail)

        val limitedOnUpload = runner(speed = FakeSpeed(up = { throw SpeedTestHttpException(429) })).run()
        assertEquals(CheckStatus.SKIPPED, limitedOnUpload.of(CheckId.SPEED).status)
        assertEquals(DiagText(R.string.diag_speed_rate_limited), limitedOnUpload.of(CheckId.SPEED).detail)
        assertEquals(DiagnosisSummary.OK, limitedOnUpload.summary)
    }

    @Test
    fun `speed - one direction failing still reports the other, both slow warns`() = runTest {
        val oneWay = runner(speed = FakeSpeed(up = { throw java.io.IOException("reset") })).run().of(CheckId.SPEED)
        assertEquals(CheckStatus.OK, oneWay.status)
        assertTrue(oneWay.value!!.contains("up -"))

        val slow = runner(
            speed = FakeSpeed(down = { SpeedTransfer(500_000, 8000) }, up = { SpeedTransfer(400_000, 8000) }),
        ).run().of(CheckId.SPEED)
        assertEquals(CheckStatus.WARN, slow.status)
        assertEquals(DiagText(R.string.diag_speed_slow_hint), slow.hint)
    }
}
