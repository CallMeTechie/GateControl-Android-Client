package com.gatecontrol.android.rdp.freerdp

import com.freerdp.freerdpcore.domain.BookmarkBase
import com.freerdp.freerdpcore.domain.ManualBookmark
import com.gatecontrol.android.rdp.RdpConnectionParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdpBookmarkBuilderTest {

    private val baseParams = RdpConnectionParams(
        host = "10.0.0.10",
        port = 3389,
        username = "admin",
        password = "secret",
        domain = "CORP",
        resolutionWidth = 1920,
        resolutionHeight = 1080,
        colorDepth = 32,
        redirectClipboard = true,
        redirectPrinters = false,
        redirectDrives = true,
        audioMode = "local",
        adminSession = false,
        routeName = "Dev Server"
    )

    @Test
    fun `build sets hostname port credentials and label`() {
        val bookmark = RdpBookmarkBuilder.build(baseParams)

        assertTrue(bookmark is ManualBookmark)
        val m = bookmark as ManualBookmark
        assertEquals("10.0.0.10", m.hostname)
        assertEquals(3389, m.port)
        assertEquals("admin", m.username)
        assertEquals("secret", m.password)
        assertEquals("CORP", m.domain)
        assertEquals("Dev Server", m.label)
    }

    @Test
    fun `build maps screen settings`() {
        val bookmark = RdpBookmarkBuilder.build(baseParams)
        val screen = bookmark.screenSettings
        assertEquals(1920, screen.width)
        assertEquals(1080, screen.height)
        assertEquals(32, screen.colors)
    }

    @Test
    fun `build disables bandwidth-heavy perf flags`() {
        val bookmark = RdpBookmarkBuilder.build(baseParams)
        val perf = bookmark.performanceFlags
        assertFalse(perf.wallpaper)
        assertFalse(perf.theming)
        assertFalse(perf.fullWindowDrag)
        assertFalse(perf.menuAnimations)
        assertFalse(perf.remoteFX)
    }

    @Test
    fun `build handles null credentials`() {
        val bookmark = RdpBookmarkBuilder.build(
            baseParams.copy(username = null, password = null, domain = null)
        )
        val m = bookmark as ManualBookmark
        assertEquals("", m.username)
        assertEquals("", m.password)
        assertEquals("", m.domain)
    }

    @Test
    fun `build enables console session when adminSession is true`() {
        val bookmark = RdpBookmarkBuilder.build(baseParams.copy(adminSession = true))
        assertTrue(bookmark.advancedSettings.consoleMode)
    }
}
