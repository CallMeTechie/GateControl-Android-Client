package com.gatecontrol.android.ui.media

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The head of the Medien tab: the dot and the way, never the server's host or name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class MediaSourceLineUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val one = MediaSource("a", MediaSourceKind.JELLYFIN, "nas-1.example.test", "http://192.168.1.20:8096", token = "tok")
    private val two = MediaSource("b", MediaSourceKind.JELLYFIN, "nas-2.example.test", "http://192.168.1.21:8096", token = "tok")

    @Test
    fun `one server - only the dot and the way`() {
        val ui = MediaHomeUi(sources = listOf(one), source = one, servers = listOf(one))
        rule.setContent { GateControlTheme(darkTheme = false) { SourceLine(ui, MediaLink(atHome = true), onSelect = {}) } }
        rule.onNodeWithTag(SOURCE_DOT_TAG).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_link_home)).assertIsDisplayed()
        rule.onNode(hasText("example.test", substring = true)).assertDoesNotExist()
        rule.onNode(hasText("Jellyfin", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `several servers - the kind to switch, no host`() {
        val ui = MediaHomeUi(sources = listOf(one, two), source = one, servers = listOf(one, two))
        rule.setContent { GateControlTheme(darkTheme = false) { SourceLine(ui, MediaLink(atHome = true), onSelect = {}) } }
        rule.onNodeWithText("Jellyfin").assertIsDisplayed()
        rule.onNodeWithContentDescription(context.getString(R.string.media_switch_source)).assertIsDisplayed()
        rule.onNode(hasText("example.test", substring = true)).assertDoesNotExist()
    }
}
