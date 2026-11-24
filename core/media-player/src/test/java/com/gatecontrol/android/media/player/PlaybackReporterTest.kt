package com.gatecontrol.android.media.player

import androidx.media3.common.C
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The progress to the source: started once, while it plays, stopped once (at the end or when left). */
class PlaybackReporterTest {

    private data class Sent(val at: Int, val event: PlaybackEvent, val positionMs: Long, val paused: Boolean, val durationMs: Long)

    private val sent = mutableListOf<Sent>()
    private val reporter = PlaybackReporter { at, event, positionMs, paused, durationMs -> sent += Sent(at, event, positionMs, paused, durationMs) }
    private var position = 0L
    private var playing = false
    private var state = Player.STATE_BUFFERING
    private var length = C.TIME_UNSET
    private val player = mockk<Player> {
        every { currentPosition } answers { position }
        every { isPlaying } answers { playing }
        every { playbackState } answers { state }
        every { duration } answers { length }
        every { playWhenReady } returns true
    }

    @Test
    fun `nothing before it is ready, then start, progress, pause and stop once`() {
        reporter.tick(player, 0)
        reporter.playingChanged(player, 0, true)
        reporter.stop(player, 0)
        assertTrue(sent.isEmpty())

        state = Player.STATE_READY
        length = 6_480_000
        reporter.ready(player, 0)
        reporter.ready(player, 0)
        assertEquals(listOf(Sent(0, PlaybackEvent.START, 0, false, 6_480_000)), sent)
        assertTrue(reporter.started)

        playing = true
        position = 42_000
        reporter.tick(player, 0)
        reporter.playingChanged(player, 0, false)
        assertEquals(Sent(0, PlaybackEvent.PROGRESS, 42_000, false, 6_480_000), sent[1])
        assertEquals(Sent(0, PlaybackEvent.PROGRESS, 42_000, true, 6_480_000), sent[2])

        reporter.stop(player, 0)
        reporter.stop(player, 0)
        assertEquals(Sent(0, PlaybackEvent.STOP, 42_000, true, 6_480_000), sent.last())
        assertEquals(4, sent.size)
        assertFalse(reporter.started)
    }

    @Test
    fun `the end - stopped at its length, the index as it was`() {
        state = Player.STATE_READY
        reporter.ready(player, 3)
        length = 2_700_000
        position = 2_699_000
        state = Player.STATE_ENDED
        // no progress after the end
        reporter.playingChanged(player, 3, false)
        reporter.ended(player, 3)
        assertEquals(listOf(PlaybackEvent.START, PlaybackEvent.STOP), sent.map { it.event })
        assertEquals(Sent(3, PlaybackEvent.STOP, 2_700_000, false, 2_700_000), sent.last())
        assertEquals(0L, PlaybackReporter.lengthOf(mockk<Player> { every { duration } returns C.TIME_UNSET }))
    }
}
