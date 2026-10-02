package com.gatecontrol.android.support

import android.content.Context
import android.net.ConnectivityManager
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.SupportBundleUploader
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SupportBundleCollectorTest {

    private val wgKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val psk = "FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE="
    private val token = "gc_" + "f00dfeed".repeat(6)

    @TempDir
    lateinit var tmp: File

    private fun collector(connected: Boolean = true): SupportBundleCollector {
        val logDir = File(tmp, "logs").apply { mkdirs() }
        File(logDir, "gatecontrol.log.1").writeText((0 until 1500).joinToString("\n") { "2026-10-02 09:00:00.000 I/Old: line $it" } + "\n")
        File(logDir, "gatecontrol.log").writeText(
            (0 until 1000).joinToString("\n") { "2026-10-02 10:00:00.000 I/Tunnel: line $it" } +
                "\n2026-10-02 10:00:01.000 W/Api: X-API-Token: $token rejected" +
                "\n2026-10-02 10:00:02.000 E/TunnelManager: connect failed: no handshake\n",
        )
        val context = mockk<Context> {
            every { cacheDir } returns tmp
            every { getSystemService(ConnectivityManager::class.java) } returns null
        }
        val setup = mockk<SetupRepository> {
            every { getWireGuardConfig() } returns "[Interface]\nPrivateKey = $wgKey\nAddress = 10.8.0.9/32\n[Peer]\nPresharedKey = $psk\nEndpoint = gate.example.com:51820"
            every { getPeerId() } returns 7
        }
        val tunnel = mockk<TunnelManager> {
            every { state } returns MutableStateFlow(if (connected) TunnelState.Connected(connectedSince = 1_790_000_000_000L) else TunnelState.Disconnected)
            every { stats } returns MutableStateFlow(TunnelStats(rxBytes = 10, txBytes = 20, lastHandshakeEpoch = 1_790_000_000L - 42))
        }
        return SupportBundleCollector(context, setup, tunnel)
    }

    private val settings = mapOf(
        "serverUrl" to "https://gate.example.com",
        "apiToken" to token,
        "peerId" to 7,
        "theme" to "dark",
        "splitTunnelMode" to "OFF",
    )

    @Test
    fun `collects all sections and leaks no secret`() {
        val b = collector().collect("1.5.0", "de", settings, nowMillis = 1_790_000_000_000L)

        assertEquals(1, b["schema"])
        assertEquals("user", b["reason"])
        val client = b["client"] as Map<*, *>
        assertEquals("android", client["product"])
        assertEquals("1.5.0", client["version"])
        assertEquals("android", client["platform"])
        assertEquals("de", client["locale"])

        val tunnel = b["tunnel"] as Map<*, *>
        assertEquals(true, tunnel["connected"])
        assertEquals(42L, tunnel["lastHandshakeAgeSec"])
        assertEquals(10L, tunnel["rxBytes"])

        val s = b["settings"] as Map<*, *>
        assertFalse(s.containsKey("apiToken"))
        assertEquals("https://gate.example.com", s["serverUrl"])

        val wg = b["wireguardConfig"] as String
        assertTrue(wg.contains("PrivateKey = [REDACTED]"), wg)
        assertTrue(wg.contains("Address = 10.8.0.9/32"), wg)

        val logs = b["logs"] as Map<*, *>
        val lines = logs["lines"] as List<*>
        assertEquals(SupportBundleCollector.MAX_LOG_LINES, lines.size)
        assertEquals(true, logs["truncated"])
        assertTrue((lines.last() as String).contains("connect failed"))
        val errors = b["errors"] as List<*>
        assertTrue(errors.any { (it as String).contains("connect failed") })
        assertTrue(errors.any { (it as String).contains("W/Api") })

        val network = b["network"] as Map<*, *>
        assertTrue(network["interfaces"] is List<*>)
        assertNull(network["activeNetwork"])

        val json = SupportBundleUploader.toJson(b)
        for (secret in listOf(wgKey, psk, token)) assertFalse(json.contains(secret), "leak: $secret")
    }

    @Test
    fun `admin request reason and disconnected tunnel`() {
        val b = collector(connected = false).collect("1.5.0", "en", emptyMap(), reason = "admin_request")
        assertEquals("admin_request", b["reason"])
        assertEquals(false, (b["tunnel"] as Map<*, *>)["connected"])
    }

    @Test
    fun `readLogs takes the newest lines across rotation`() {
        val dir = File(tmp, "l").apply { mkdirs() }
        File(dir, "gatecontrol.log.1").writeText("a\nb\n")
        File(dir, "gatecontrol.log").writeText("c\nd\n")
        val tail = SupportBundleCollector.readLogs(dir, maxLines = 3)
        assertEquals(listOf("b", "c", "d"), tail.lines)
        assertEquals(4, tail.totalLines)
        assertTrue(tail.truncated)
    }

    @Test
    fun `request holder follows the heartbeat`() {
        SupportRequestHolder.clear()
        SupportRequestHolder.update(true, "2026-10-02 10:00:00")
        assertEquals("2026-10-02 10:00:00", SupportRequestHolder.request.value)
        SupportRequestHolder.update(null, null) // older server: unchanged
        assertEquals("2026-10-02 10:00:00", SupportRequestHolder.request.value)
        SupportRequestHolder.update(true, null)
        assertEquals("requested", SupportRequestHolder.request.value)
        SupportRequestHolder.update(false, null)
        assertNull(SupportRequestHolder.request.value)
    }
}
