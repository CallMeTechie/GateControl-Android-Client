package com.gatecontrol.android.media

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where Medien starts playing (stage 4c fills it): "Weiter ab …", "Von vorn", a tap on a card of "Weiterschauen".
 * [queue] is what the player goes through (an episode with the rest of its series, the sound, subtitle and quality
 * chosen on the detail page).
 */
interface MediaPlayback {
    /** Whether this build has the player; false: the pages say it comes with the next update. */
    val available: Boolean

    fun play(context: Context, queue: MediaQueue)
}

/** Until the player of stage 4c: nothing plays, the pages say so. */
@Singleton
class MediaPlaybackPending @Inject constructor() : MediaPlayback {
    override val available: Boolean = false

    override fun play(context: Context, queue: MediaQueue) = Unit
}
