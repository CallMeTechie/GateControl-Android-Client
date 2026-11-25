package com.gatecontrol.android.ui.media

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.jellyfin.JellyfinAuthorize
import com.gatecontrol.android.ui.theme.GateControlTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Medien › Quellen (mockup 6): the sources with who is signed in, the settings (mobile data limit 3 Mbit/s), and
 * "Gerät anmelden" sending the TV's code with this phone's access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class MediaSourcesUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val jellyfin = MediaSource("jf", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", userId = "u1", userName = "Mila", token = "tok")
    private val share = MediaSource("nas", MediaSourceKind.SMB, "\\\\nas\\filme", "smb://nas/filme", userName = "medien")

    @Test
    fun `the sources, the limit on mobile data and a TV signed in by its code`() {
        val store = mockk<MediaSourceStore>(relaxed = true) { every { sources } returns MutableStateFlow(listOf(jellyfin, share)) }
        val hub = mockk<MediaHub>(relaxed = true) {
            every { this@mockk.store } returns store
            every { currentSource() } returns jellyfin
        }
        coEvery { hub.authorize(jellyfin, "731205") } returns JellyfinAuthorize.CONFIRMED
        val settings = mockk<MediaPhoneSettings>(relaxed = true) {
            every { mobileCap } returns MutableStateFlow(3_000_000L)
            every { startCard } returns MutableStateFlow(true)
            every { offlineQuality } returns MutableStateFlow(com.gatecontrol.android.media.offline.OfflineQuality.P1080)
            every { offlineLimitGb } returns MutableStateFlow(20)
        }
        val vm = MediaSourcesViewModel(hub, settings, fakeOffline())
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                MediaSourcesScreen(onBack = {}, onAdd = {}, onSignInAgain = {}, viewModel = vm)
            }
        }
        rule.onNodeWithText("Jellyfin · DS918+").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_signed_in_as, "Mila")).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_share) + " · \\\\nas\\filme").assertIsDisplayed()
        rule.onNodeWithText("3 Mbit/s").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_mobile_cap_desc, com.gatecontrol.android.media.MediaPhoneLogic.gbPerHour(3_000_000L))).assertIsDisplayed()

        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(CODE_TAG))).performScrollTo().performTextInput("731205")
        rule.onNodeWithText(context.getString(R.string.media_authorize_confirm)).performScrollTo().performClick()
        rule.waitForIdle()
        coVerify { hub.authorize(jellyfin, "731205") }
        rule.onNodeWithText(context.getString(R.string.media_authorize_done)).performScrollTo().assertIsDisplayed()
    }
}
