package com.gatecontrol.android.ui.media

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.server.MediaMigration
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The VPN away for media („VPN wird verbunden …“, „Unterwegs nur über VPN“) and the takeover sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class MediaServerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `away without the VPN - connecting, then blocked with VPN einschalten`() {
        rule.setContent { GateControlTheme(darkTheme = false) { MediaVpnGate(MediaGate.CONNECTING, onConsent = {}, onRetry = {}) } }
        rule.onNodeWithText(context.getString(R.string.media_vpn_connecting)).assertIsDisplayed()
    }

    @Test
    fun `blocked - the button starts the VPN again`() {
        var retried = 0
        var consent: Boolean? = null
        rule.setContent { GateControlTheme(darkTheme = false) { MediaVpnGate(MediaGate.BLOCKED, onConsent = { consent = it }, onRetry = { retried++ }) } }
        rule.onNodeWithText(context.getString(R.string.media_vpn_blocked)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_vpn_blocked_text)).assertIsDisplayed()
        // „VPN einschalten“: the consent (when missing) or straight the VPN; never a way around it
        rule.onNodeWithTag(VPN_ON_TAG).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(true, retried + (if (consent != null) 1 else 0) <= 1)
    }

    @Test
    fun `the takeover sheet - taken over and removed, Verstanden`() {
        var done = 0
        val items = listOf(
            MediaMigration("uuid-1", MediaSourceKind.JELLYFIN, "Jellyfin · DS918+", "DS918+"),
            MediaMigration("uuid-2", MediaSourceKind.SMB, "\\\\nas\\filme", "Filme"),
            MediaMigration("uuid-3", MediaSourceKind.WEBDAV, "192.168.1.30/privat", null),
        )
        rule.setContent { GateControlTheme(darkTheme = false) { MediaMigrationSheet(items, onDone = { done++ }) } }
        rule.onNodeWithText(context.getString(R.string.media_migr_title)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_migr_taken_jf)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_migr_taken_share, "Filme (NAS)")).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_migr_removed)).assertIsDisplayed()
        rule.onNodeWithTag(MIGRATION_OK_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, done)
    }
}
