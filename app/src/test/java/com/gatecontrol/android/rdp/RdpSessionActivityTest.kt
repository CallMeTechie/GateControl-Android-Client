package com.gatecontrol.android.rdp

import android.content.Intent
import android.view.WindowManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdpSessionActivityTest {

    private fun buildIntent(
        host: String? = "10.0.0.5",
        port: Int = 3389,
        username: String? = "admin",
        password: String? = "secret",
        domain: String? = "CORP",
        width: Int = 1920,
        height: Int = 1080,
        colorDepth: Int = 32,
        redirectClipboard: Boolean = true,
        redirectPrinters: Boolean = false,
        redirectDrives: Boolean = true,
        audioMode: String? = "local",
        adminSession: Boolean = false,
        routeName: String? = "Test Server"
    ): Intent = mockk(relaxed = true) {
        every { getStringExtra("rdp_host") } returns host
        every { getIntExtra("rdp_port", 3389) } returns port
        every { getStringExtra("rdp_username") } returns username
        every { getStringExtra("rdp_password") } returns password
        every { getStringExtra("rdp_domain") } returns domain
        every { getIntExtra("rdp_resolution_width", 0) } returns width
        every { getIntExtra("rdp_resolution_height", 0) } returns height
        every { getIntExtra("rdp_color_depth", 32) } returns colorDepth
        every { getBooleanExtra("rdp_redirect_clipboard", false) } returns redirectClipboard
        every { getBooleanExtra("rdp_redirect_printers", false) } returns redirectPrinters
        every { getBooleanExtra("rdp_redirect_drives", false) } returns redirectDrives
        every { getStringExtra("rdp_audio_mode") } returns audioMode
        every { getBooleanExtra("rdp_admin_session", false) } returns adminSession
        every { getStringExtra("rdp_route_name") } returns routeName
    }

    @Test
    fun `parseConnectionParams extracts all extras from intent`() {
        val params = RdpSessionActivity.parseConnectionParams(buildIntent())

        assertNotNull(params)
        assertEquals("10.0.0.5", params!!.host)
        assertEquals(3389, params.port)
        assertEquals("admin", params.username)
        assertEquals("secret", params.password)
        assertEquals("CORP", params.domain)
        assertEquals(1920, params.resolutionWidth)
        assertEquals(1080, params.resolutionHeight)
        assertEquals(32, params.colorDepth)
        assertTrue(params.redirectClipboard)
        assertEquals("local", params.audioMode)
    }

    @Test
    fun `parseConnectionParams returns null when host is missing`() {
        val intent = buildIntent(host = null)
        val params = RdpSessionActivity.parseConnectionParams(intent)
        assertNull(params)
    }

    @Test
    fun `FLAG_SECURE constant is correct`() {
        assertEquals(
            WindowManager.LayoutParams.FLAG_SECURE,
            RdpSessionActivity.WINDOW_FLAG_SECURE
        )
    }
}
