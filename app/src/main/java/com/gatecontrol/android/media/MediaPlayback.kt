package com.gatecontrol.android.media

import android.content.Context

/**
 * Where Medien starts playing: "Weiter ab …", "Von vorn", a tap on a card of "Weiterschauen". [queue] is what the
 * player goes through (an episode with the rest of its series, the sound, subtitle and quality chosen on the detail
 * page). The phone's player is [MediaPlayerSession] (stage 4c).
 */
interface MediaPlayback {
    fun play(context: Context, queue: MediaQueue)
}
