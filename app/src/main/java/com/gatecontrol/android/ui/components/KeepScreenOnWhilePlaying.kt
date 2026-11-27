package com.gatecontrol.android.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.Player
import java.util.WeakHashMap

/**
 * Keeps the screen on while [player] really plays ([Player.isPlaying]): paused, at its end, after an error, while it
 * waits for data and once it has gone (the screen left, the player changed) the screen may go off again. Through
 * [View.setKeepScreenOn] of the window it is shown in — the screen, a dialog over it (the vault's viewer), the same in
 * full screen and picture-in-picture. One for every video of the app beside the media player (MediaPlayerActivity
 * keeps its window on by itself).
 */
@Composable
fun KeepScreenOnWhilePlaying(player: Player?) {
    val view = LocalView.current
    DisposableEffect(player, view) {
        val watch = player?.let { ScreenOnWhilePlaying(view, it) }
        onDispose { watch?.stop() }
    }
}

/** Holds [view]'s screen on as long as [player] plays, until [stop]. Main thread only. */
internal class ScreenOnWhilePlaying(
    private val view: View,
    private val player: Player,
    private val holds: ScreenOnHolds = ScreenOnHolds.Shared,
) : Player.Listener {
    private var holding = false

    init {
        player.addListener(this)
        hold(player.isPlaying)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = hold(isPlaying)

    fun stop() {
        player.removeListener(this)
        hold(false)
    }

    private fun hold(on: Boolean) {
        if (on == holding) return
        holding = on
        if (on) holds.acquire(view) else holds.release(view)
    }
}

/**
 * Who keeps a view's screen on: on with the first, off after the last, so two videos of one window (a page coming in
 * while another goes) never take it from each other. A view kept on before by itself stays so. Main thread only.
 */
internal class ScreenOnHolds {
    private class Hold(val foreign: Boolean, var count: Int = 0)

    private val holds = WeakHashMap<View, Hold>()

    fun count(view: View): Int = holds[view]?.count ?: 0

    fun acquire(view: View) {
        val hold = holds.getOrPut(view) { Hold(foreign = view.keepScreenOn) }
        hold.count++
        if (hold.count == 1 && !hold.foreign) view.keepScreenOn = true
    }

    fun release(view: View) {
        val hold = holds[view] ?: return
        hold.count--
        if (hold.count > 0) return
        holds.remove(view)
        if (!hold.foreign) view.keepScreenOn = false
    }

    companion object {
        val Shared = ScreenOnHolds()
    }
}
