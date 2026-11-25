package com.gatecontrol.android.ui.media

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaDeviceCaps
import com.gatecontrol.android.media.MediaNet
import com.gatecontrol.android.media.MediaPhoneLogic
import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaPlayLine
import com.gatecontrol.android.media.MediaPlayerChoices
import com.gatecontrol.android.media.MediaPlayerUi
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaQualitySetting
import com.gatecontrol.android.media.MediaQueue
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's player (stage 4c, mockups 4) without a player: upright the info chips and the next episodes, the sheet
 * "Ton & Untertitel" with the NAS's notes and the quality, turned the info line and "Nächste Folge", the countdown and
 * the note on the data volume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class MediaPlayerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun episode(n: Int) = MediaTitle("e$n", "jf", MediaTitle.Kind.EPISODE, "Folge $n", seriesId = "s1", seriesName = "Station Eisvogel", season = 2, episode = n, runtimeMs = 2_640_000)

    private val deDts = MediaStream(1, MediaStream.Type.AUDIO, codec = "dts", language = "de", channels = 6)
    private val enTrueHd = MediaStream(2, MediaStream.Type.AUDIO, codec = "truehd", language = "en", channels = 8)
    private val dePgs = MediaStream(3, MediaStream.Type.SUBTITLE, codec = "PGSSUB", language = "de", textual = false)

    private val choices = MediaPlayerChoices(
        audio = listOf(deDts, enTrueHd),
        subtitles = listOf(dePgs),
        audioIndex = 1,
        subtitleIndex = MediaPlay.SUBTITLES_OFF,
        qualities = MediaQualitySetting.entries.toList(),
        quality = MediaQualitySetting.AUTO,
        canConvert = true,
        caps = MediaDeviceCaps.fromDecoders(listOf("audio/mp4a-latm"), hdrScreen = false, name = "GateControl"),
    )

    private val ui = MediaPlayerUi(
        queue = MediaQueue("jf", listOf(episode(4), episode(5), episode(6)), 0),
        at = 0,
        sourceName = "DS918+",
        loading = false,
        line = MediaPlayLine(MediaNet.HOME, video = "1080p", audioTo = "AAC 5.1"),
        choices = choices,
    )

    @Test
    fun `upright the chips, the next episodes and the sheet with what the NAS does`() {
        val audio = mutableListOf<Int>()
        val subtitle = mutableListOf<Int>()
        val played = mutableListOf<Int>()
        rule.setContent {
            GateControlTheme(darkTheme = true) {
                MediaPlayerScreen(ui, player = null, landscape = false, pip = false, mobileCap = 3_000_000, actions = MediaPlayerActions(onAudio = { audio += it }, onSubtitle = { subtitle += it }, onPlayAt = { played += it }))
            }
        }
        rule.onNodeWithText(context.getString(R.string.media_player_line_direct_video, "1080p")).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_player_line_audio, "AAC 5.1")).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_player_net_home)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_player_episode, 2, 4) + " · Jellyfin · DS918+").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_player_up_next)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("6. Folge 6").performScrollTo().performClick()
        assertEquals(listOf(2), played)

        rule.onNodeWithTag(TRACKS_TAG).performClick()
        rule.onNodeWithTag(SHEET_TAG).assertIsDisplayed()
        // both converted on the NAS (the phone has no DTS or TrueHD), the picture subtitle burnt in
        assertEquals(2, rule.onAllNodes(hasText(context.getString(R.string.media_player_audio_converts, "5.1"))).fetchSemanticsNodes().size)
        rule.onNodeWithText(context.getString(R.string.media_subtitle_burn_in)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_player_quality_auto) + " · " + context.getString(R.string.media_player_quality_home)).assertIsDisplayed()
        rule.onNode(hasText("TrueHD 7.1", substring = true)).performClick()
        assertEquals(listOf(2), audio)
        rule.onNodeWithTag(TRACKS_TAG).performClick()
        rule.onNode(hasText("PGS", substring = true)).performClick()
        assertEquals(listOf(3), subtitle)
    }

    @Test
    fun `the quality steps in the sheet`() {
        val chosen = mutableListOf<MediaQualitySetting>()
        rule.setContent {
            GateControlTheme(darkTheme = true) {
                MediaPlayerScreen(ui, player = null, landscape = false, pip = false, mobileCap = null, actions = MediaPlayerActions(onQuality = { chosen += it }))
            }
        }
        rule.onNodeWithTag(TRACKS_TAG).performClick()
        rule.onNodeWithTag(QUALITY_TAG).performScrollTo().performClick()
        val step = context.getString(R.string.media_player_quality_fixed, MediaQuality.resolution(4_000_000), MediaQuality.label(4_000_000))
        rule.onNodeWithText(step).performScrollTo().performClick()
        assertEquals(listOf(MediaQualitySetting.MBIT_4), chosen)
    }

    @Test
    fun `turned on mobile data the info line, Next episode and the lock`() {
        val next = mutableListOf<Unit>()
        val away = ui.copy(line = MediaPlayLine(MediaNet.CELLULAR, measuredBps = 6_000_000, convertedBps = 3_000_000, converted = true, limitBps = 3_000_000))
        rule.setContent {
            GateControlTheme(darkTheme = true) {
                MediaPlayerScreen(away, player = null, landscape = true, pip = false, mobileCap = 3_000_000, actions = MediaPlayerActions(onNext = { next += Unit }))
            }
        }
        val line = context.getString(R.string.media_player_net_cellular) + " · 6 Mbit/s · " + context.getString(R.string.media_player_line_converted, "720p", "3 Mbit/s")
        rule.onNodeWithText(line).assertIsDisplayed()
        rule.onNodeWithText("Station Eisvogel").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_player_quality_chip, context.getString(R.string.media_player_quality_auto))).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_player_next)).performClick()
        assertEquals(1, next.size)

        rule.onNodeWithContentDescription(context.getString(R.string.media_player_lock)).performClick()
        rule.onNodeWithTag(CONTROLS_TAG).assertDoesNotExist()
    }

    @Test
    fun `the countdown to the next episode`() {
        val now = mutableListOf<Unit>()
        val cancel = mutableListOf<Unit>()
        rule.setContent {
            GateControlTheme(darkTheme = true) {
                MediaPlayerScreen(ui.copy(countdown = 8), player = null, landscape = true, pip = false, mobileCap = 3_000_000, actions = MediaPlayerActions(onNext = { now += Unit }, onCancelCountdown = { cancel += Unit }))
            }
        }
        rule.onNodeWithTag(COUNTDOWN_TAG).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_player_next_in, 8)).assertIsDisplayed()
        rule.onNodeWithText("Station Eisvogel · S2 E5 · Folge 5").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_player_next_now)).performClick()
        rule.onNodeWithText(context.getString(R.string.media_player_next_cancel)).performClick()
        assertEquals(1, now.size)
        assertEquals(1, cancel.size)
    }

    @Test
    fun `the note on the data volume before the first playback on mobile data`() {
        val read = mutableListOf<Unit>()
        rule.setContent {
            GateControlTheme(darkTheme = true) {
                MediaPlayerScreen(ui.copy(mobileHint = true), player = null, landscape = false, pip = false, mobileCap = 3_000_000, actions = MediaPlayerActions(onMobileHintRead = { read += Unit }))
            }
        }
        rule.onNodeWithText(context.getString(R.string.media_player_mobile_text, "3 Mbit/s", MediaPhoneLogic.gbPerHour(3_000_000))).assertIsDisplayed()
        rule.onNodeWithTag(MOBILE_HINT_TAG).performClick()
        assertTrue(read.isNotEmpty())
    }
}
