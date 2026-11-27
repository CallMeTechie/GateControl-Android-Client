package com.gatecontrol.android.ui.components

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A video keeps the screen on only while it really plays: on with playing, off again with a pause, at its end, after
 * an error and once the video is left — two videos of one window never take it from each other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeepScreenOnWhilePlayingTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A player whose state the test sets: playing = play wanted and ready. */
    @OptIn(UnstableApi::class)
    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var wanted = false
            set(value) { field = value; invalidateState() }
        var stage = Player.STATE_IDLE
            set(value) { field = value; invalidateState() }
        var error: PlaybackException? = null
            set(value) { field = value; invalidateState() }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (error != null) Player.STATE_IDLE else stage)
            .setPlayerError(error)
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            wanted = playWhenReady
            return Futures.immediateVoidFuture()
        }

        fun start() { stage = Player.STATE_READY; wanted = true }
    }

    private lateinit var view: View

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun show(player: () -> Player?) {
        rule.setContent {
            view = LocalView.current
            KeepScreenOnWhilePlaying(player())
        }
        idle()
    }

    @Test
    fun `playing keeps the screen on, a pause lets it go off`() {
        val player = FakePlayer()
        show { player }
        assertFalse("not yet playing", view.keepScreenOn)

        player.start(); idle()
        assertTrue("playing", view.keepScreenOn)

        player.pause(); idle()
        assertFalse("paused", view.keepScreenOn)

        player.start(); idle()
        assertTrue("playing again", view.keepScreenOn)
    }

    @Test
    fun `at the end the screen may go off`() {
        val player = FakePlayer()
        show { player }
        player.start(); idle()
        assertTrue(view.keepScreenOn)

        player.stage = Player.STATE_ENDED; idle()
        assertFalse("ended", view.keepScreenOn)
    }

    @Test
    fun `after an error the screen may go off`() {
        val player = FakePlayer()
        show { player }
        player.start(); idle()
        assertTrue(view.keepScreenOn)

        player.error = PlaybackException("test", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED); idle()
        assertFalse("error", view.keepScreenOn)
    }

    @Test
    fun `waiting for data lets the screen go off until it plays again`() {
        val player = FakePlayer()
        show { player }
        player.start(); idle()
        player.stage = Player.STATE_BUFFERING; idle()
        assertFalse("buffering", view.keepScreenOn)
        player.stage = Player.STATE_READY; idle()
        assertTrue("playing again", view.keepScreenOn)
    }

    @Test
    fun `leaving the video lets the screen go off`() {
        val player = FakePlayer().apply { start() }
        var shown by mutableStateOf(true)
        rule.setContent {
            view = LocalView.current
            if (shown) KeepScreenOnWhilePlaying(player)
        }
        idle()
        assertTrue(view.keepScreenOn)

        shown = false; idle()
        assertFalse("left", view.keepScreenOn)
        // the left player no longer reaches the screen
        player.pause(); player.start(); idle()
        assertFalse(view.keepScreenOn)
    }

    @Test
    fun `a new player takes over, the old one no longer counts`() {
        val first = FakePlayer().apply { start() }
        val second = FakePlayer()
        var current by mutableStateOf<Player?>(first)
        show { current }
        assertTrue(view.keepScreenOn)

        current = second; idle()
        assertFalse("the new one does not play yet", view.keepScreenOn)
        second.start(); idle()
        assertTrue(view.keepScreenOn)
        first.pause(); idle()
        assertTrue("the old one is gone", view.keepScreenOn)
        current = null; idle()
        assertFalse(view.keepScreenOn)
    }

    @Test
    fun `two videos of one window do not take the screen from each other`() {
        val a = FakePlayer().apply { start() }
        val b = FakePlayer().apply { start() }
        var showA by mutableStateOf(true)
        rule.setContent {
            view = LocalView.current
            if (showA) KeepScreenOnWhilePlaying(a)
            KeepScreenOnWhilePlaying(b)
        }
        idle()
        assertTrue(view.keepScreenOn)

        showA = false; idle()
        assertTrue("b still plays", view.keepScreenOn)
        b.pause(); idle()
        assertFalse(view.keepScreenOn)
    }

    @Test
    fun `a view kept on by itself stays on`() {
        val holds = ScreenOnHolds()
        val v = View(rule.activity).apply { keepScreenOn = true }
        holds.acquire(v)
        assertEquals(1, holds.count(v))
        holds.release(v)
        assertEquals(0, holds.count(v))
        assertTrue(v.keepScreenOn)
    }
}
