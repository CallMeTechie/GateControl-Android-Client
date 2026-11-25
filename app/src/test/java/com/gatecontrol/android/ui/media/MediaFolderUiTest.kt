package com.gatecontrol.android.ui.media

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaDeviceCaps
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaPlayback
import com.gatecontrol.android.media.MediaShareProbes
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.jellyfin.JellyfinFolder
import com.gatecontrol.android.media.jellyfin.JellyfinFolderPage
import com.gatecontrol.android.media.jellyfin.JellyfinLibrary
import io.mockk.coEvery
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.ShareProgress
import com.gatecontrol.android.media.share.ShareLibrary
import com.gatecontrol.android.ui.theme.GateControlTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The page "Ordner" (mockup 5): the path bar "\\nas\filme › Familie" with "SMB · Zuhause", the folders with what they
 * hold, the videos with size, length, "noch 19 Min." and "Ton DTS: am Handy kein Ton", the note on the way, the search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class MediaFolderUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val smb = MediaSource("nas", MediaSourceKind.SMB, "\\\\nas\\filme", "smb://nas/filme", pairWith = "dav")
    private val dav = MediaSource("dav", MediaSourceKind.WEBDAV, "nas", "https://192.168.1.20:5006/filme", pairWith = "nas")
    private val files = mapOf(
        "Familie/Urlaub 2025/Strand.mp4" to ByteArray(10),
        "Familie/Schulfeste/Sommerfest.mkv" to ByteArray(10),
        "Familie/Geburtstag Oma.mkv" to ByteArray(10),
        "Familie/Silvester am See.DTS.mkv" to ByteArray(10),
    )

    @Test
    fun `a folder of the share as in the mockup`() {
        val progress = MapProgress().apply { setProgress("nas|Familie/Geburtstag Oma.mkv", ShareProgress(1_380_000, 2_520_000, false, 5)) }
        val lib = ShareLibrary(smb, MemFs(files, MediaSourceKind.SMB), progress, "nas")
        val store = mockk<MediaSourceStore>(relaxed = true) {
            every { source("nas") } returns smb
            every { source("dav") } returns dav
            every { sources } returns MutableStateFlow(listOf(smb, dav))
        }
        val hub = mockk<MediaHub>(relaxed = true) {
            every { this@mockk.store } returns store
            every { shares() } returns listOf(smb)
            every { link } returns MutableStateFlow(MediaLink(atHome = true))
            every { library(smb) } returns lib
            every { caps() } returns MediaDeviceCaps.fromDecoders(listOf("video/avc", "audio/mp4a-latm"), hdrScreen = false, name = "GateControl")
        }
        val vm = MediaFolderViewModel(hub, MediaPlayRequests(hub, mockk<MediaPlayback>(relaxed = true), fakeOffline()), MediaShareProbes(Dispatchers.Unconfined), mockk<MediaPictures>(relaxed = true))
        rule.setContent {
            GateControlTheme(darkTheme = false) { MediaFolderPage(viewModel = vm, libraries = MediaLibraryFolderViewModel(hub)) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Familie").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("SMB · Zuhause").assertIsDisplayed()
        rule.onNodeWithText("\\\\nas\\filme").assertIsDisplayed()
        rule.onNodeWithText("Urlaub 2025").assertIsDisplayed()
        rule.onAllNodesWithText("1 Video").assertCountEquals(2)
        rule.onNodeWithText("Geburtstag Oma.mkv").assertIsDisplayed()
        rule.onNode(hasText("42 Min. · noch 19 Min.", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("Ton DTS: am Handy kein Ton", substring = true)).assertIsDisplayed()
        rule.onNode(hasText(context.getString(R.string.media_folder_note_pair_home), substring = true)).assertIsDisplayed()

        rule.onNodeWithContentDescription(context.getString(R.string.media_folder_search)).performClick()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(FOLDER_SEARCH_TAG))).performTextInput("strand")
        rule.waitForIdle()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        rule.waitForIdle()
        rule.onNodeWithText("Strand.mp4").assertIsDisplayed()
    }

    private val jelly = MediaSource("jf", MediaSourceKind.JELLYFIN, "nas-1", "http://192.168.1.20:8096", userId = "u1", token = "tok")

    /** Jellyfin alone (no share): the page "Ordner" with [lib] as the server's library. */
    private fun jellyfinOnly(lib: JellyfinLibrary, onDetail: (MediaTitle) -> Unit = {}) {
        val store = mockk<MediaSourceStore>(relaxed = true) {
            every { sources } returns MutableStateFlow(listOf(jelly))
        }
        val hub = mockk<MediaHub>(relaxed = true) {
            every { this@mockk.store } returns store
            every { shares() } returns emptyList()
            every { link } returns MutableStateFlow(MediaLink(atHome = true))
            every { library(jelly) } returns lib
            every { route(jelly) } returns MediaRoute.HOME
        }
        val vm = MediaFolderViewModel(hub, MediaPlayRequests(hub, mockk<MediaPlayback>(relaxed = true), fakeOffline()), MediaShareProbes(Dispatchers.Unconfined), mockk<MediaPictures>(relaxed = true))
        rule.setContent {
            GateControlTheme(darkTheme = false) { MediaFolderPage(onDetail = onDetail, viewModel = vm, libraries = MediaLibraryFolderViewModel(hub)) }
        }
        rule.waitForIdle()
    }

    @Test
    fun `Jellyfin alone - its libraries, a folder, a film opens its page`() {
        val film = MediaTitle("m1", "jf", MediaTitle.Kind.MOVIE, "Kobaltnacht", year = 2024, runtimeMs = 6_480_000)
        val episode = MediaTitle("e1", "jf", MediaTitle.Kind.VIDEO, "Sommerfest")
        val lib = mockk<JellyfinLibrary>(relaxed = true) {
            coEvery { views() } returns MediaResult.Ok(listOf(JellyfinFolder("v1", "Filme"), JellyfinFolder("v2", "Heimvideos")))
            coEvery { children("v1", any()) } returns MediaResult.Ok(JellyfinFolderPage(listOf(JellyfinFolder("f1", "Familie")), listOf(film)))
            coEvery { children("f1", any()) } returns MediaResult.Ok(JellyfinFolderPage(emptyList(), listOf(episode)))
        }
        val opened = mutableListOf<String>()
        jellyfinOnly(lib, onDetail = { opened += it.id })
        rule.onNodeWithText("Filme").assertIsDisplayed()
        rule.onNodeWithText("Heimvideos").assertIsDisplayed()
        rule.onNodeWithTag(FOLDER_ROOTS_EMPTY_TAG).assertDoesNotExist()

        rule.onNodeWithText("Filme").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Familie").assertIsDisplayed()
        rule.onNodeWithText("Kobaltnacht").assertIsDisplayed()
        rule.onNode(hasText("2024 · 1 Std. 48 Min.", substring = true)).assertIsDisplayed()

        rule.onNodeWithText("Familie").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sommerfest").assertIsDisplayed()
        // the path bar: back to the library
        rule.onNodeWithText("Filme").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Kobaltnacht").performClick()
        assertEquals(listOf("m1"), opened)
        // up: the list of libraries again
        rule.onNodeWithContentDescription(context.getString(R.string.media_folder_all_shares)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Heimvideos").assertIsDisplayed()
    }

    @Test
    fun `nothing to show - a line says so, not an empty card`() {
        val lib = mockk<JellyfinLibrary>(relaxed = true) {
            coEvery { views() } returns MediaResult.Ok(emptyList())
        }
        jellyfinOnly(lib)
        rule.onNodeWithTag(FOLDER_ROOTS_EMPTY_TAG).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.media_folders_none)).assertIsDisplayed()
    }

    @Test
    fun `the server not reached - why, with a retry`() {
        val lib = mockk<JellyfinLibrary>(relaxed = true) {
            coEvery { views() } returns MediaResult.Offline
        }
        jellyfinOnly(lib)
        rule.onNodeWithText(context.getString(R.string.media_problem_offline)).assertIsDisplayed()
        rule.onNodeWithTag(FOLDER_ROOTS_EMPTY_TAG).assertDoesNotExist()
        coEvery { lib.views() } returns MediaResult.Ok(listOf(JellyfinFolder("v1", "Filme")))
        rule.onNodeWithText(context.getString(R.string.media_retry)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Filme").assertIsDisplayed()
    }
}
