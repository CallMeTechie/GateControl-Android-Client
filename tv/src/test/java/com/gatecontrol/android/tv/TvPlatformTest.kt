package com.gatecontrol.android.tv

import com.gatecontrol.android.network.ClientPlatform
import com.gatecontrol.android.network.UpdateQuery
import com.gatecontrol.android.tv.boot.BootPlan
import com.gatecontrol.android.tv.di.TvModule
import com.gatecontrol.android.tv.ui.pair.TvQr
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What makes the TV the TV towards the server, the plan after a boot and the pairing QR code. */
class TvPlatformTest {

    @Test
    fun `the TV binds android-tv for header, pairing and update check`() {
        val platform = TvModule.provideClientPlatform()
        assertEquals(ClientPlatform.ANDROID_TV, platform)
        assertEquals("android-tv", platform.wire)
        assertEquals(UpdateQuery(client = "android-tv", platform = "android-tv"), platform.updateQuery)
        // sideloaded: always its own download
        assertTrue(TvModule.provideApkUpdateChannel().ownDownload())
    }

    @Test
    fun `boot - tunnel by default, the app only when wanted`() {
        assertEquals(
            BootPlan.Plan(connectTunnel = true, openApp = false),
            BootPlan.decide(paired = true, autostartTunnel = TvSettings.DEFAULT_AUTOSTART_TUNNEL, openOnBoot = TvSettings.DEFAULT_OPEN_ON_BOOT, vpnConsent = true, tunnelActive = false),
        )
        assertFalse(BootPlan.decide(paired = false, autostartTunnel = true, openOnBoot = false, vpnConsent = true, tunnelActive = false).connectTunnel)
        assertFalse(BootPlan.decide(paired = true, autostartTunnel = false, openOnBoot = false, vpnConsent = true, tunnelActive = false).connectTunnel)
        // without the consent of the system the backend cannot start
        assertFalse(BootPlan.decide(paired = true, autostartTunnel = true, openOnBoot = false, vpnConsent = false, tunnelActive = false).connectTunnel)
        // always-on VPN was faster
        assertFalse(BootPlan.decide(paired = true, autostartTunnel = true, openOnBoot = false, vpnConsent = true, tunnelActive = true).connectTunnel)
        assertTrue(BootPlan.decide(paired = false, autostartTunnel = true, openOnBoot = true, vpnConsent = false, tunnelActive = false).openApp)
    }

    @Test
    fun `the QR code reads back as the pairing link`() {
        val link = "gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com%3A8443&id=AbCdEfGhIjKlMnOpQrSt_-"
        val m = TvQr.matrix(link)
        // render with a quiet zone of 4 modules, 4 px per module
        val scale = 4
        val quiet = 4
        val size = (m.width + 2 * quiet) * scale
        val pixels = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) {
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    pixels[((y + quiet) * scale + dy) * size + (x + quiet) * scale + dx] = 0xFF000000.toInt()
                }
            }
        }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels))))
        assertEquals(link, read.text)
    }
}
