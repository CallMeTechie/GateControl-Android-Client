package com.gatecontrol.android.ui.components

import android.app.Application
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A WhatsApp contact with several status pictures: the viewer showed the first picture for every segment — the loader
 * kept its state across a new media id and never loaded the next one. A new key loads afresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GcLoadedTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aNewKeyLoadsItsOwnValue() {
        var id by mutableStateOf("story-1")
        val loads = mutableListOf<String>()
        rule.setContent {
            val current = id
            val v = rememberLoaded(current, 1080, peek = { null }, load = { loads += current; "picture of $current" })
            BasicText(v ?: "loading")
        }
        rule.onNodeWithText("picture of story-1").assertExists()
        id = "story-2"
        rule.waitForIdle()
        rule.onNodeWithText("picture of story-2").assertExists()
        id = "story-3"
        rule.waitForIdle()
        rule.onNodeWithText("picture of story-3").assertExists()
        assertEquals(listOf("story-1", "story-2", "story-3"), loads)
    }

    @Test
    fun aCachedValueShowsAtOnceWithoutLoading() {
        var id by mutableStateOf("story-1")
        val loads = mutableListOf<String>()
        rule.setContent {
            val current = id
            val v = rememberLoaded(current, peek = { if (current == "story-2") "cached $current" else null }, load = { loads += current; "loaded $current" })
            BasicText(v ?: "loading")
        }
        rule.onNodeWithText("loaded story-1").assertExists()
        id = "story-2"
        rule.waitForIdle()
        rule.onNodeWithText("cached story-2").assertExists()
        assertEquals(listOf("story-1"), loads)
    }
}
