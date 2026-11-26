package com.gatecontrol.android.ui.media

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.media.server.MediaServerState
import com.gatecontrol.android.ui.theme.GateControlTheme
import io.mockk.coVerify
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Medien › Quellen as a display (mockup „Quellen (Anzeige)“): the admin's sources with their status, the hint, no „+“
 * and no „Gerät anmelden“; „Auf diesem Gerät“ with the mobile data limit and the downloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class MediaSourcesUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val jellyfin = MediaSource(
        "jf-1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", userId = "u1", userName = "mila", token = "tok",
        managed = true, remoteId = "jf-1", deviceId = "gc-1234", deviceName = "GateControl · Pixel von Mila", linkedUserId = "u1",
    )
    private val smb = MediaSource("sh-1", MediaSourceKind.SMB, "Filme", "smb://192.168.1.20/filme", userName = "medien-lesen", password = "pw", managed = true, remoteId = "sh-1", pairWith = "sh-1~webdav")
    private val dav = MediaSource("sh-1~webdav", MediaSourceKind.WEBDAV, "Filme", "https://192.168.1.20:5006/filme", userName = "medien-lesen", password = "pw", managed = true, remoteId = "sh-1", pairWith = "sh-1")
    private val other = MediaSource("jf-2", MediaSourceKind.JELLYFIN, "Büro", "http://192.168.1.30:8096", managed = true, remoteId = "jf-2", problem = "no_mapping")

    @Test
    fun `the server's sources with their status, nothing to add, the settings of this phone`() {
        val store = mockk<MediaSourceStore>(relaxed = true) { every { sources } returns MutableStateFlow(listOf(jellyfin, smb, dav, other)) }
        val hub = mockk<MediaHub>(relaxed = true) {
            every { this@mockk.store } returns store
            every { link } returns MutableStateFlow(MediaLink(atHome = false))
            every { route(any()) } returns MediaRoute.VPN
        }
        val servers = mockk<MediaServerSources>(relaxed = true) {
            every { state } returns MutableStateFlow(MediaServerState(known = true, tab = true, lastContact = System.currentTimeMillis() - 3 * 60_000))
        }
        val settings = mockk<MediaPhoneSettings>(relaxed = true) {
            every { mobileCap } returns MutableStateFlow(3_000_000L)
            every { startCard } returns MutableStateFlow(true)
            every { offlineQuality } returns MutableStateFlow(com.gatecontrol.android.media.offline.OfflineQuality.P1080)
            every { offlineLimitGb } returns MutableStateFlow(20)
            every { aheadGb } returns MutableStateFlow(4)
            every { awayOriginal } returns MutableStateFlow(com.gatecontrol.android.media.MediaAwayOriginal.ASK)
        }
        val vm = MediaSourcesViewModel(hub, settings, fakeOffline(), servers)
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                MediaSourcesScreen(onBack = {}, viewModel = vm)
            }
        }
        // „Neu laden“: the server's list again
        rule.onNodeWithTag(RELOAD_TAG).performClick()
        rule.waitForIdle()
        coVerify { servers.refresh(true) }
        verify { servers.refreshSoon() }
        rule.onNodeWithText("Jellyfin · DS918+").assertIsDisplayed()
        rule.onNodeWithText(
            context.getString(R.string.media_status_signed_in_auto, "mila") + " · " + context.getString(R.string.media_way_vpn),
        ).assertIsDisplayed()
        // the pair once, by the admin's name; away WebDAV
        rule.onNodeWithText("Filme (NAS)").assertIsDisplayed()
        rule.onNodeWithText(
            context.getString(R.string.media_status_share, context.getString(R.string.media_status_share_now, "WebDAV"), context.getString(R.string.media_pair_note)),
        ).assertIsDisplayed()
        // a problem in plain words
        rule.onNodeWithText(context.getString(R.string.media_pp_no_mapping)).assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithText(context.getString(R.string.media_status_ready)).fetchSemanticsNodes().size)
        rule.onNodeWithText(context.getString(R.string.media_sources_admin) + " " + context.getString(R.string.media_sources_stand, context.getString(R.string.media_ago_min, 3)))
            .assertIsDisplayed()
        // nothing of the old set-up
        assertEquals(0, rule.onAllNodesWithText("Add source").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Sign in a device").fetchSemanticsNodes().size)
        // „Auf diesem Gerät“
        rule.onNodeWithText(context.getString(R.string.media_this_device), ignoreCase = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("3 Mbit/s").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(OFFLINE_SETTINGS_TAG).performScrollTo().assertIsDisplayed()
        // „Unterwegs mitladen“ with its space, and what plays when the original does not go through
        rule.onNodeWithTag(AHEAD_SETTING_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_away_setting)).performScrollTo().assertIsDisplayed()

        // a source tapped: its address and way
        rule.onNodeWithText("Jellyfin · DS918+").performScrollTo().performClick()
        rule.onNodeWithText(context.getString(R.string.media_detail_device, "GateControl · Pixel von Mila")).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_detail_close)).performClick()

    }
}
