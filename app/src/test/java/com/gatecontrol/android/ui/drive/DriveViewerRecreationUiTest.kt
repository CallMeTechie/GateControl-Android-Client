package com.gatecontrol.android.ui.drive

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gatecontrol.android.drive.DNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A viewer of Drive while the activity is made anew (a fold, an unfold, a turn of the phone): the file opened is open
 * again ([rememberKept]: a DNode does not fit into a Bundle), the player is the same one (kept by the view model, it
 * plays on) and it is released exactly once — when the viewer closes, not with the old activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriveViewerRecreationUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    internal class FakePlayer { var released = 0 }

    /** As DriveMediaViewModel: the player kept, released with the view model as well. */
    internal class Players : ViewModel() {
        var made = 0
        val kept = DriveKeptPlayer<FakePlayer> { it.released++ }
        override fun onCleared() = kept.clear()
    }

    private val film = DNode(7, "mine:7", 1, "Film.mp4", false, 10, "video/mp4", null, 1, null, null, null)
    private val other = DNode(8, "mine:8", 1, "Urlaub.mp4", false, 10, "video/mp4", null, 1, null, null, null)

    private var players: Players? = null
    private var shown: FakePlayer? = null
    private var gone = 0
    private var open: (DNode?) -> Unit = {}

    /** A screen with its viewer as Drive's screens have it: a dialog over the page, its video in the view model. */
    @Composable
    private fun Screen() {
        val vm: Players = viewModel { Players() }
        players = vm
        var viewing by rememberKept<DNode?> { null }
        open = { viewing = it }
        Box(Modifier.fillMaxSize()) { Text("page") }
        viewing?.let { n ->
            Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                val player = remember(n.id) { vm.kept.get(DriveVideoKey(n.id, n.rev, 0)) { vm.made++; FakePlayer() } }
                shown = player
                ReleaseWhenGone(vm.kept, player) { gone++ }
                Text(n.name, Modifier.testTag("viewer"))
            }
        }
    }

    private fun show() = rule.activityRule.scenario.onActivity { it.setContent { Screen() } }

    private fun recreate() {
        rule.activityRule.scenario.recreate()
        show()
        rule.waitForIdle()
    }

    @Test
    fun `the viewer stays open over a new activity, with the same player, not released`() {
        show()
        rule.runOnIdle { open(film) }
        rule.onNodeWithText("Film.mp4").assertExists()
        val vm = players!!
        val player = shown!!

        recreate()

        rule.onNodeWithTag("viewer").assertExists()
        rule.onNodeWithText("Film.mp4").assertExists()
        assertSame("the same view model", vm, players)
        assertSame("the same player: the film plays on", player, shown)
        assertEquals(1, vm.made)
        assertEquals(0, player.released)
        assertEquals(0, gone)

        // and again (unfolded, then turned)
        recreate()
        assertSame(player, shown)
        assertEquals(1, vm.made)
        assertEquals(0, player.released)
    }

    @Test
    fun `closed after a new activity - the player released exactly once`() {
        show()
        rule.runOnIdle { open(film) }
        rule.waitForIdle()
        val vm = players!!
        val player = shown!!
        recreate()

        rule.runOnIdle { open(null) }
        rule.waitForIdle()
        rule.onNodeWithTag("viewer").assertDoesNotExist()
        assertEquals(1, player.released)
        assertEquals("the place it was left kept once", 1, gone)

        // the page goes (its view model with it): nothing released twice
        rule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
        assertEquals(1, player.released)
        assertEquals(1, vm.made)
    }

    @Test
    fun `another video - the last player released once, the new one kept over a new activity`() {
        show()
        rule.runOnIdle { open(film) }
        rule.waitForIdle()
        val first = shown!!
        rule.runOnIdle { open(other) }
        rule.waitForIdle()
        val second = shown!!
        assertEquals(1, first.released)
        assertEquals(0, second.released)

        recreate()
        rule.onNodeWithText("Urlaub.mp4").assertExists()
        assertSame(second, shown)
        assertEquals(1, first.released)
        assertEquals(0, second.released)
        assertEquals(2, players!!.made)
    }

    @Test
    fun `the app left (not made anew) - the player released once`() {
        show()
        rule.runOnIdle { open(film) }
        rule.waitForIdle()
        val player = shown!!
        assertNotNull(player)
        rule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
        assertEquals(1, player.released)
    }

    @Test
    fun `a viewer closed is not open again after a new activity`() {
        show()
        rule.runOnIdle { open(film) }
        rule.waitForIdle()
        rule.runOnIdle { open(null) }
        rule.waitForIdle()
        recreate()
        rule.onNodeWithTag("viewer").assertDoesNotExist()
        rule.onNodeWithText("page").assertExists()
    }
}
