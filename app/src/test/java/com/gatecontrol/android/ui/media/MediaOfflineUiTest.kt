package com.gatecontrol.android.ui.media

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaDeviceCaps
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaNetwork
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaPlayback
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.offline.MediaOfflineLogic
import com.gatecontrol.android.media.offline.OfflineAllowed
import com.gatecontrol.android.media.offline.OfflineItem
import com.gatecontrol.android.media.offline.OfflineQuality
import com.gatecontrol.android.media.offline.OfflineState
import com.gatecontrol.android.ui.theme.GateControlTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Download for offline (question 22 a, mockup 7): the page "Offline" with "Belegt … / 20 GB", bereit / lädt 45 % /
 * wartet auf WLAN; on the detail page "Laden" with the qualities, or the note when Jellyfin does not allow it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class MediaOfflineUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val jellyfin = MediaSource("jf", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", userId = "u1", userName = "Mila", token = "tok")

    private fun episode(n: Int) = MediaTitle("e$n", "jf", MediaTitle.Kind.EPISODE, "Folge $n", seriesId = "s1", seriesName = "Station Eisvogel", season = 2, episode = n)

    private fun item(t: MediaTitle, q: OfflineQuality, state: OfflineState, size: Long = 0, loaded: Long = 0, expected: Long = 0, added: Long = 0) = OfflineItem(
        id = MediaOfflineLogic.idOf(t.sourceId, t.id), sourceId = t.sourceId, title = t, quality = q, state = state, size = size, loaded = loaded, expected = expected, addedAt = added,
    )

    private val kobalt = MediaTitle("m1", "jf", MediaTitle.Kind.MOVIE, "Kobaltnacht", year = 2025, runtimeMs = 6_480_000)

    private fun settings() = mockk<MediaPhoneSettings>(relaxed = true) {
        every { offlineQuality } returns MutableStateFlow(OfflineQuality.P1080)
        every { offlineLimitGb } returns MutableStateFlow(20)
        every { mobileCap } returns MutableStateFlow(3_000_000L)
    }

    @Test
    fun `the page Offline as in the mockup`() {
        val items = MutableStateFlow(
            listOf(
                item(kobalt, OfflineQuality.P1080, OfflineState.READY, size = 3_100_000_000, added = 3),
                item(episode(5), OfflineQuality.P720, OfflineState.LOADING, loaded = 450_000_000, expected = 950_000_000, added = 2),
                item(episode(6), OfflineQuality.P720, OfflineState.QUEUED, expected = 950_000_000, added = 2),
                item(MediaTitle("m2", "jf", MediaTitle.Kind.MOVIE, "Sternwarte 9"), OfflineQuality.P1080, OfflineState.QUEUED, added = 1),
            ),
        )
        val offline = fakeOffline(items).also { every { it.wifi } returns MutableStateFlow(false) }
        val vm = MediaOfflineViewModel(offline, mockk<MediaPlayRequests>(relaxed = true), settings(), mockk<MediaPictures>(relaxed = true))
        rule.setContent { GateControlTheme(darkTheme = false) { MediaOfflineScreen(onBack = {}, onSettings = {}, onOpen = {}, viewModel = vm) } }
        rule.waitForIdle()
        rule.onNodeWithText("Belegt").assertIsDisplayed()
        rule.onNodeWithTag(OFFLINE_USED_TAG).assertIsDisplayed()
        rule.onNode(hasText("/ 20 GB", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Nur im WLAN · Qualität 1080p (NAS rechnet vorher um)").assertIsDisplayed()
        rule.onNodeWithText("Kobaltnacht").assertIsDisplayed()
        rule.onNode(hasText("1080p · 3", substring = true) and hasText("GB · bereit", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Station Eisvogel · S2 F5–F6").assertIsDisplayed()
        rule.onNode(hasText("720p ·", substring = true) and hasText("lädt 23 %", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Sternwarte 9").assertIsDisplayed()
        rule.onNodeWithText("wartet auf WLAN").assertIsDisplayed()
        rule.onNodeWithTag(OFFLINE_TAG).performScrollToNode(hasText(context.getString(R.string.media_offline_note)))
        rule.onNodeWithText(context.getString(R.string.media_offline_note)).assertIsDisplayed()
    }

    @Test
    fun `a cut-off converted download says it starts over`() {
        val cut = item(kobalt, OfflineQuality.P720, OfflineState.QUEUED, expected = 950_000_000, added = 1).copy(restarted = true)
        val offline = fakeOffline(MutableStateFlow(listOf(cut))).also { every { it.wifi } returns MutableStateFlow(true) }
        val vm = MediaOfflineViewModel(offline, mockk<MediaPlayRequests>(relaxed = true), settings(), mockk<MediaPictures>(relaxed = true))
        rule.setContent { GateControlTheme(darkTheme = false) { MediaOfflineScreen(onBack = {}, onSettings = {}, onOpen = {}, viewModel = vm) } }
        rule.waitForIdle()
        rule.onNodeWithText("wartet · unterbrochen, beginnt von vorn (die NAS rechnet neu um)").assertIsDisplayed()
    }

    private fun detail(allowed: OfflineAllowed, items: MutableStateFlow<List<OfflineItem>> = MutableStateFlow(emptyList())): Pair<MediaDetailViewModel, com.gatecontrol.android.media.offline.MediaOffline> {
        val video = MediaStream(0, MediaStream.Type.VIDEO, codec = "hevc", width = 3840, height = 2160)
        val dts = MediaStream(1, MediaStream.Type.AUDIO, codec = "dts", language = "ger", channels = 6)
        val version = MediaVersion("v1", container = "mkv", bitrate = 40_000_000, streams = listOf(video, dts), defaultAudio = 1, size = 32_400_000_000)
        val lib = FakeLibrary(jellyfin, details = mapOf("m1" to MediaDetail(kobalt, listOf(version))))
        val store = mockk<MediaSourceStore>(relaxed = true) {
            every { source("jf") } returns jellyfin
            every { trackChoice(any()) } returns null
        }
        val hub = mockk<MediaHub>(relaxed = true) {
            every { this@mockk.store } returns store
            every { link } returns MutableStateFlow(MediaLink(atHome = true))
            every { gate } returns MutableStateFlow(com.gatecontrol.android.media.MediaGate.OPEN)
            every { library(jellyfin) } returns lib
            every { caps() } returns MediaDeviceCaps.fromDecoders(listOf("video/avc", "audio/mp4a-latm"), hdrScreen = false, name = "GateControl")
        }
        val offline = fakeOffline(items, allowed)
        val requests = MediaPlayRequests(hub, mockk<MediaPlayback>(relaxed = true), offline)
        val network = mockk<MediaNetwork> { every { cellular() } returns false }
        val vm = MediaDetailViewModel(SavedStateHandle(mapOf("source" to "jf", "id" to "m1", "name" to "Kobaltnacht")), hub, requests, settings(), network, mockk(relaxed = true), offline)
        return vm to offline
    }

    @Test
    fun `Laden on the detail page offers the original and the steps, with sizes and the sound's warning`() {
        val (vm, offline) = detail(OfflineAllowed.YES)
        rule.setContent { GateControlTheme(darkTheme = false) { MediaDetailScreen(onBack = {}, onOpen = {}, viewModel = vm) } }
        rule.waitForIdle()
        rule.onNodeWithTag(LOAD_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Laden für offline").assertIsDisplayed()
        rule.onNode(hasText("Original · 4K", substring = true) and hasText("32,4 GB", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Ton DTS: am Handy kein Ton, lieber umgerechnet laden").assertIsDisplayed()
        rule.onNode(hasText("720p · etwa", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("1080p · etwa", substring = true)).performClick()
        rule.waitForIdle()
        verify { offline.enqueue(listOf(kobalt), OfflineQuality.P1080, 1) }
    }

    @Test
    fun `Jellyfin does not allow it - the note instead of Laden, a ready one says so`() {
        val (vm, _) = detail(OfflineAllowed.NO)
        rule.setContent { GateControlTheme(darkTheme = false) { MediaDetailScreen(onBack = {}, onOpen = {}, viewModel = vm) } }
        rule.waitForIdle()
        rule.onNode(hasTestTag(LOAD_TAG)).assertDoesNotExist()
        rule.onNodeWithTag(DETAIL_TAG).performScrollToNode(hasTestTag(NOT_ALLOWED_TAG))
        rule.onNodeWithText(context.getString(R.string.media_offline_not_allowed)).assertIsDisplayed()
    }

    @Test
    fun `a ready download on the detail page`() {
        val items = MutableStateFlow(listOf(item(kobalt, OfflineQuality.P1080, OfflineState.READY, size = 3_100_000_000)))
        val (vm, _) = detail(OfflineAllowed.YES, items)
        rule.setContent { GateControlTheme(darkTheme = false) { MediaDetailScreen(onBack = {}, onOpen = {}, viewModel = vm) } }
        rule.waitForIdle()
        rule.onNodeWithTag(DETAIL_TAG).performScrollToNode(hasTestTag(OFFLINE_STATUS_TAG))
        rule.onNode(hasText("Offline verfügbar · 1080p · 3", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Offline").assertIsDisplayed()
    }
}
