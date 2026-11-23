package com.gatecontrol.android.tv.player

import android.view.KeyEvent
import java.util.Locale

/** What a key of the remote does in the player. */
enum class TvPlayerAction {
    TOGGLE,
    PLAY,
    PAUSE,
    SEEK_BACK,
    SEEK_FORWARD,
    NEXT,
    PREVIOUS,
    /** The panel "Ton & Untertitel" on the right (▲ or the menu key, mockup 9). */
    TRACKS,
    /** The bar and the line with the details (▼). */
    SHOW_CONTROLS,
    /** Zurück: the panel, else the bar, else the player closes. */
    BACK,
    /** ■ (some remotes): the player closes. */
    STOP,
}

/**
 * The keys of the Fire TV remote (and of other TV remotes, keyboards over Bluetooth) in the player, without Android's
 * player UI: D-pad, OK, Zurück, ☰ and the media keys ⏯ ⏪ ⏩. Pure, unit-tested.
 */
object TvPlayerKeys {
    /** One step of ◀ ▶ ⏪ ⏩ (mockup 4: "Spulen ±10 s"). */
    const val SEEK_MS = 10_000L

    /** Held down (the key repeats): after this many repeats a step is [SEEK_FAST_MS]. */
    const val FAST_AFTER_REPEATS = 5
    const val SEEK_FAST_MS = 30_000L

    /** The bar fades after this long without a key (mockup 4: "blendet sich nach 4 s aus"). */
    const val CONTROLS_HIDE_MS = 4_000L

    /** The action of [keyCode] on key down; null: not the player's (the system handles it, e.g. Home, volume). */
    fun action(keyCode: Int): TvPlayerAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_HEADSETHOOK,
        -> TvPlayerAction.TOGGLE
        KeyEvent.KEYCODE_MEDIA_PLAY -> TvPlayerAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> TvPlayerAction.PAUSE
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
        KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
        -> TvPlayerAction.SEEK_BACK
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
        KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
        -> TvPlayerAction.SEEK_FORWARD
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> TvPlayerAction.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> TvPlayerAction.PREVIOUS
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
        -> TvPlayerAction.TRACKS
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> TvPlayerAction.SHOW_CONTROLS
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> TvPlayerAction.BACK
        KeyEvent.KEYCODE_MEDIA_STOP -> TvPlayerAction.STOP
        else -> null
    }

    /** How far one seek step goes while the key repeats [repeatCount] times. */
    fun seekStep(repeatCount: Int): Long = if (repeatCount >= FAST_AFTER_REPEATS) SEEK_FAST_MS else SEEK_MS

    /**
     * Where a seek by [deltaMs] from [positionMs] lands: never before 0, never past the end of a video of known
     * [durationMs] (≤ 0: unknown, only the start is held). The very end would end the video at once: a second before.
     */
    fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val target = (positionMs + deltaMs).coerceAtLeast(0)
        if (durationMs <= 0) return target
        return target.coerceAtMost((durationMs - 1_000).coerceAtLeast(0))
    }

    /** "04:27" / "1:12:30": the times of the bar. */
    fun time(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }
}

/** What the photo viewer does with a key: ◀ ▶ step, ⏯ / OK runs the slide show, Zurück closes. */
enum class TvPhotoAction { PREVIOUS, NEXT, SLIDESHOW, BACK }

object TvPhotoKeys {
    fun action(keyCode: Int): TvPhotoAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN,
        -> TvPhotoAction.PREVIOUS
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP,
        -> TvPhotoAction.NEXT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_SPACE,
        -> TvPhotoAction.SLIDESHOW
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MEDIA_STOP -> TvPhotoAction.BACK
        else -> null
    }
}
