package com.gatecontrol.android.diagnosis

import com.gatecontrol.android.di.NowMillis
import com.gatecontrol.android.network.DnsCheckResponse
import com.gatecontrol.android.network.SpeedTestAvailability
import com.gatecontrol.android.network.SpeedTestHttpException
import com.gatecontrol.android.network.SpeedTransfer
import com.gatecontrol.android.tunnel.TunnelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.SocketTimeoutException
import javax.inject.Inject

/**
 * Runs the seven checks in order (internet, server, tunnel, handshake, dns,
 * mtu, speed). Every check has its own timeout and never aborts the others;
 * checks that need the tunnel are skipped while it is down. Progress is
 * reported through `onUpdate` after every step.
 */
class DiagnosisRunner @Inject constructor(
    private val network: NetworkProbe,
    private val server: ServerProbe,
    private val tunnel: TunnelProbe,
    private val commands: CommandRunner,
    private val speed: SpeedProbe,
    @NowMillis private val now: () -> Long,
) {

    /** State shared by the checks of one run. */
    private class Run {
        var tunnelUp = false
        var serverReachable = false
        var dnsCheck: DnsCheckResponse? = null
        var dnsCheckDone = false
    }

    suspend fun run(onUpdate: (List<CheckResult>) -> Unit = {}): DiagnosisReport {
        val startedAt = now()
        val results = CheckId.entries.map(CheckResult::pending).toMutableList()
        val run = Run()
        onUpdate(results.toList())

        for (id in CheckId.entries) {
            results[id.ordinal] = CheckResult(id, CheckStatus.RUNNING)
            onUpdate(results.toList())
            results[id.ordinal] = guarded(id) { check(id, run) }
            onUpdate(results.toList())
        }
        return DiagnosisReport(startedAt, now(), results.toList())
    }

    private suspend fun guarded(id: CheckId, block: suspend () -> CheckResult): CheckResult = try {
        withTimeout(timeoutFor(id)) { block() }
    } catch (e: TimeoutCancellationException) {
        Timber.w("Diagnosis: %s timed out", id.key)
        checkTimedOut(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("Diagnosis: %s failed (%s)", id.key, e.javaClass.simpleName)
        checkCrashed(id, e)
    }

    private suspend fun check(id: CheckId, run: Run): CheckResult = when (id) {
        CheckId.INTERNET -> evaluateInternet(network.internet())
        CheckId.SERVER -> checkServer(run)
        CheckId.TUNNEL -> {
            val state = tunnel.state()
            run.tunnelUp = state is TunnelState.Connected
            evaluateTunnel(state, now())
        }
        CheckId.HANDSHAKE -> if (!run.tunnelUp) skippedNoTunnel(id) else checkHandshake()
        CheckId.DNS -> if (!run.tunnelUp) skippedNoTunnel(id) else evaluateDns(network.vpnDns(), dnsCheck(run)?.vpnDns?.let(::parseDnsList))
        CheckId.MTU -> if (!run.tunnelUp) skippedNoTunnel(id) else checkMtu(run)
        CheckId.SPEED -> if (!run.serverReachable) speedSkipped(SpeedSkip.NO_SERVER) else checkSpeed()
    }

    // ─── server ──────────────────────────────────────────────────────────

    private suspend fun checkServer(run: Run): CheckResult {
        if (!server.isConfigured()) return serverNotConfigured()
        val latencies = mutableListOf<Long>()
        var error: Throwable? = null
        var retried = false
        repeat(PING_COUNT) {
            if (error != null && retried) return@repeat
            if (error != null) {
                // A pooled connection from before a VPN or network change hangs
                // until it times out: one more try on fresh connections.
                retried = true
                server.resetConnections()
            }
            try {
                val ms = withTimeoutOrNull(PING_TIMEOUT_MS) { server.ping() }
                if (ms == null) {
                    if (error == null) error = SocketTimeoutException("ping timed out")
                } else {
                    latencies += ms
                    error = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One answer is enough for a latency; without any, report the first error.
                if (error == null) error = e
            }
        }
        run.serverReachable = latencies.isNotEmpty()
        return evaluateServer(latencies, error.takeIf { latencies.isEmpty() })
    }

    private suspend fun dnsCheck(run: Run): DnsCheckResponse? {
        if (!run.dnsCheckDone) {
            run.dnsCheckDone = true
            run.dnsCheck = if (!server.isConfigured()) null else try {
                withTimeoutOrNull(DNS_CHECK_TIMEOUT_MS) { server.dnsCheck() }?.takeIf { it.ok }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Diagnosis: dns-check failed (%s)", e.javaClass.simpleName)
                null
            }
        }
        return run.dnsCheck
    }

    // ─── handshake ───────────────────────────────────────────────────────

    private fun sample(): HandshakeSample {
        val stats = tunnel.stats()
        return HandshakeSample(stats?.lastHandshakeEpoch ?: 0L, stats?.rxBytes ?: 0L, now())
    }

    private suspend fun checkHandshake(): CheckResult {
        val first = sample()
        if (!first.needsRecheck) return evaluateHandshake(first, null)
        // WireGuard only shakes hands when there is traffic: make some, look again.
        if (server.isConfigured()) {
            try {
                withTimeoutOrNull(PING_TIMEOUT_MS) { server.ping() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Only meant to send packets through the tunnel.
            }
        }
        delay(HANDSHAKE_RECHECK_DELAY_MS)
        return evaluateHandshake(first, sample())
    }

    // ─── mtu ─────────────────────────────────────────────────────────────

    private suspend fun checkMtu(run: Run): CheckResult {
        val gateway = dnsCheck(run)?.gatewayIp?.trim()
        if (!isIpv4(gateway)) return mtuNoGateway()
        val mtu = tunnel.tunnelMtu()
        val deadline = now() + MTU_BUDGET_MS
        val search = searchMtu(
            tunnelMtu = mtu,
            hasTime = { now() < deadline },
        ) { size ->
            val payload = (size - ICMP_IPV4_OVERHEAD).coerceAtLeast(0)
            parsePing(
                commands.run(
                    listOf(PING_BINARY, "-M", "do", "-c", "1", "-W", "1", "-s", payload.toString(), gateway!!),
                    PING_PROCESS_TIMEOUT_MS,
                ),
            )
        }
        return evaluateMtu(search)
    }

    // ─── speed ───────────────────────────────────────────────────────────

    private suspend fun checkSpeed(): CheckResult {
        val info = try {
            speed.info()
        } catch (e: SpeedTestHttpException) {
            return when (e.code) {
                404 -> speedSkipped(SpeedSkip.UNSUPPORTED)
                429 -> speedSkipped(SpeedSkip.RATE_LIMITED)
                else -> evaluateSpeed(null, null, null)
            }
        }
        val available = when (info) {
            SpeedTestAvailability.Unsupported -> return speedSkipped(SpeedSkip.UNSUPPORTED)
            SpeedTestAvailability.RateLimited -> return speedSkipped(SpeedSkip.RATE_LIMITED)
            is SpeedTestAvailability.Available -> info
        }

        val down = transfer(minOf(SPEED_DOWNLOAD_MAX_BYTES, available.maxDownloadBytes)) { speed.download(it, SPEED_TIME_CAP_MS) }
        if (down is Transfer.RateLimited) return speedSkipped(SpeedSkip.RATE_LIMITED)
        val up = transfer(minOf(SPEED_UPLOAD_MAX_BYTES, available.maxUploadBytes)) { speed.upload(it, SPEED_TIME_CAP_MS) }
        if (up is Transfer.RateLimited) return speedSkipped(SpeedSkip.RATE_LIMITED)

        val downMbps = (down as? Transfer.Done)?.t?.let { mbps(it.bytes, it.millis) }
        val upMbps = (up as? Transfer.Done)?.t?.let { t ->
            uploadMillis(t.millis, t.serverMs)?.let { mbps(uploadBytes(t.bytes, t.serverBytes), it) }
        }
        return evaluateSpeed(downMbps, upMbps, available.viaTunnel)
    }

    private sealed interface Transfer {
        data class Done(val t: SpeedTransfer) : Transfer
        data object RateLimited : Transfer
        data object Failed : Transfer
    }

    private suspend fun transfer(bytes: Long, block: suspend (Long) -> SpeedTransfer): Transfer {
        if (bytes <= 0) return Transfer.Failed
        return try {
            Transfer.Done(block(bytes))
        } catch (e: SpeedTestHttpException) {
            if (e.code == 429) Transfer.RateLimited else Transfer.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Diagnosis: speed transfer failed (%s)", e.javaClass.simpleName)
            Transfer.Failed
        }
    }

    companion object {
        const val PING_COUNT = 3
        const val PING_TIMEOUT_MS = 6_000L
        const val DNS_CHECK_TIMEOUT_MS = 8_000L
        const val HANDSHAKE_RECHECK_DELAY_MS = 3_000L
        const val MTU_BUDGET_MS = 10_000L
        const val PING_PROCESS_TIMEOUT_MS = 2_500L
        const val PING_BINARY = "/system/bin/ping"

        fun timeoutFor(id: CheckId): Long = when (id) {
            CheckId.INTERNET -> 5_000L
            CheckId.SERVER -> 25_000L
            CheckId.TUNNEL -> 5_000L
            CheckId.HANDSHAKE -> 15_000L
            CheckId.DNS -> 12_000L
            CheckId.MTU -> 15_000L
            CheckId.SPEED -> 45_000L
        }
    }
}
