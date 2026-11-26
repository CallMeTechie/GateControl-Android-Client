package com.gatecontrol.android.media

import android.view.KeyEvent

/**
 * The phone's volume keys during „Auf TV/PC abspielen“: while a session runs and the receiver tells its volume they
 * change the volume there ([PhoneCast.volumeKey] in steps of [com.gatecontrol.android.media.cast.CastRemote.VOLUME_STEP],
 * "Stumm" [PhoneCast.toggleMute]) instead of the phone's; otherwise they stay the phone's own.
 */
object CastVolumeKeys {

    /** What a key does: steps up (+1) or down (-1), or [MUTE]; null: not a volume key. */
    fun steps(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> 1
        KeyEvent.KEYCODE_VOLUME_DOWN -> -1
        KeyEvent.KEYCODE_VOLUME_MUTE -> MUTE
        else -> null
    }

    const val MUTE = 0

    /** [event] for the receiver (true: taken, the phone's volume stays), else false. */
    fun handle(event: KeyEvent, cast: PhoneCast): Boolean {
        val steps = steps(event.keyCode) ?: return false
        if (!cast.hasVolume) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            when {
                steps != MUTE -> cast.volumeKey(steps)
                event.repeatCount == 0 -> cast.toggleMute()
            }
        }
        // the key's release too: the phone's own volume panel does not open
        return true
    }
}
