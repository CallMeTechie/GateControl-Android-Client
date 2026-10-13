package com.gatecontrol.android.diagnosis

import android.content.Context
import android.net.ConnectivityManager
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.SupportBundleUploader
import com.gatecontrol.android.support.SupportBundleCollector
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** "Problem melden": description and diagnosis as extra top-level keys of the support bundle. */
class DiagnosisBundleTest {

    @TempDir
    lateinit var tmp: File

    private val token = "gc_" + "f00dfeed".repeat(6)

    private fun collector(): SupportBundleCollector {
        File(tmp, "logs").mkdirs()
        val context = mockk<Context> {
            every { cacheDir } returns tmp
            every { getSystemService(ConnectivityManager::class.java) } returns null
        }
        val setup = mockk<SetupRepository> {
            every { getWireGuardConfig() } returns ""
            every { getPeerId() } returns 7
        }
        val tunnel = mockk<TunnelManager> {
            every { state } returns MutableStateFlow(TunnelState.Disconnected)
            every { stats } returns MutableStateFlow(TunnelStats())
        }
        return SupportBundleCollector(context, setup, tunnel)
    }

    /** Fake resolver: the resource name instead of the text, args appended. */
    private val resolve: (DiagText) -> String = { t -> "res#${t.id}" + t.args.joinToString("") { "|$it" } }

    private val report = DiagnosisReport(
        startedAt = 1_790_000_000_000L,
        finishedAt = 1_790_000_030_000L,
        checks = listOf(
            CheckResult(CheckId.INTERNET, CheckStatus.OK, DiagText(R.string.diag_internet_ok, "WLAN"), "wifi"),
            CheckResult(CheckId.SERVER, CheckStatus.WARN, DiagText(R.string.diag_server_ms_slow, 900L), "900 ms", DiagText(R.string.diag_server_slow_hint)),
            CheckResult(CheckId.TUNNEL, CheckStatus.OK, DiagText(R.string.diag_tunnel_connected_since, "5m")),
            CheckResult(CheckId.HANDSHAKE, CheckStatus.OK, DiagText(R.string.diag_handshake_ago_s, 4L), "4s"),
            CheckResult(CheckId.DNS, CheckStatus.OK, DiagText(R.string.diag_dns_ok, "10.8.0.1"), "10.8.0.1"),
            CheckResult(CheckId.MTU, CheckStatus.SKIPPED, DiagText(R.string.diag_mtu_no_answer), null, DiagText(R.string.diag_mtu_no_answer_hint)),
            CheckResult(CheckId.SPEED, CheckStatus.OK, DiagText(R.string.diag_speed_failed)),
        ),
    )

    @Test
    fun `payload - ids, statuses, texts and optional keys`() {
        val p = DiagnosisPayload.build(report, resolve)
        assertEquals("2026-09-21T14:13:20Z", p["startedAt"])
        assertEquals("2026-09-21T14:13:50Z", p["finishedAt"])
        assertEquals("warn", p["summary"])
        @Suppress("UNCHECKED_CAST")
        val checks = p["checks"] as List<Map<String, Any?>>
        assertEquals(listOf("internet", "server", "tunnel", "handshake", "dns", "mtu", "speed"), checks.map { it["id"] })
        assertEquals(listOf("ok", "warn", "ok", "ok", "ok", "skipped", "ok"), checks.map { it["status"] })
        val server = checks[1]
        assertEquals("res#${R.string.diag_check_server}", server["title"])
        assertEquals("res#${R.string.diag_server_ms_slow}|900", server["detail"])
        assertEquals("900 ms", server["value"])
        assertEquals("res#${R.string.diag_server_slow_hint}", server["hint"])
        // value / hint are left out when there is none
        assertFalse(checks[2].containsKey("value"))
        assertFalse(checks[0].containsKey("hint"))
    }

    @Test
    fun `payload - description is trimmed, capped and empty means absent`() {
        assertEquals("Seiten laden nicht", DiagnosisPayload.description("  Seiten laden nicht \n"))
        assertEquals(null, DiagnosisPayload.description("   "))
        assertEquals(null, DiagnosisPayload.description(null))
        assertEquals(2000, DiagnosisPayload.description("x".repeat(5000))!!.length)
    }

    @Test
    fun `bundle - extras present and redacted`() {
        val b = collector().collect(
            appVersion = "1.27.0",
            locale = "de",
            settings = mapOf("serverUrl" to "https://gate.example.com"),
            nowMillis = 1_790_000_000_000L,
            description = "  Seit heute langsam, mein Token ist $token  ",
            diagnosis = DiagnosisPayload.build(report, resolve),
        )
        assertEquals(1, b["schema"])
        val description = b["description"] as String
        assertTrue(description.startsWith("Seit heute langsam"))
        assertFalse(description.contains(token), "the description passes the redactor")
        @Suppress("UNCHECKED_CAST")
        val diagnosis = b["diagnosis"] as Map<String, Any?>
        assertEquals("warn", diagnosis["summary"])
        assertEquals(7, (diagnosis["checks"] as List<*>).size)
        assertFalse(SupportBundleUploader.toJson(b).contains(token))
    }

    @Test
    fun `bundle - extras absent for the plain support bundle`() {
        val b = collector().collect("1.27.0", "de", emptyMap(), nowMillis = 1_790_000_000_000L)
        assertFalse(b.containsKey("description"))
        assertFalse(b.containsKey("diagnosis"))

        val blank = collector().collect("1.27.0", "de", emptyMap(), description = " \n ", diagnosis = null)
        assertFalse(blank.containsKey("description"))
        assertFalse(blank.containsKey("diagnosis"))
    }
}
