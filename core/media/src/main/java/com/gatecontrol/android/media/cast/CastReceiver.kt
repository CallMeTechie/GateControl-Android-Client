package com.gatecontrol.android.media.cast

import kotlin.math.abs

/** The receiver's session: who controls it ([controller], a GateDrop device id) and what came from there last. */
data class CastReceiverSession(
    val session: String,
    val controller: String,
    val controllerName: String?,
    val lastSeq: Long,
    /** Our own next [CastMessage.State.seq]. */
    val nextSeq: Long = 1,
)

/** What the receiver plays, as far as the controller is told. */
data class CastSnapshot(
    val phase: CastPhase,
    val positionMs: Long,
    val durationMs: Long,
    val audio: String? = null,
    val subtitle: String? = null,
    val tracks: Int = 0,
)

/** The receiver's rules (GateControl TV here, the Windows app by the same contract). Pure, unit-tested. */
object CastReceiverRules {

    /** A jump of more than this against where it should be counts as a change worth a state. */
    const val JUMP_MS = 4_000L

    /**
     * A start to act on: not older than [CastProtocol.PLAY_MAX_AGE_MS] by the GateDrop send's time [sentAt] (the
     * server's clock; null: unknown, then the sender's own [CastMessage.at]) against [now].
     */
    fun freshPlay(m: CastMessage.Play, sentAt: Long?, now: Long): Boolean = fresh(sentAt ?: m.at, now, CastProtocol.PLAY_MAX_AGE_MS)

    /** The session a start makes; whoever had the receiver before loses it ([CastProtocol.ERROR_TAKEN_OVER]). */
    fun begin(m: CastMessage.Play, fromDevice: String): CastReceiverSession = CastReceiverSession(m.session, fromDevice, m.from, m.seq)

    /**
     * A command to act on: of [s]'s session, from its controller, newer than the last and fresh. The session with
     * the newer seq; null: drop it.
     */
    fun accept(s: CastReceiverSession?, m: CastMessage.Command, fromDevice: String, sentAt: Long?, now: Long): CastReceiverSession? {
        if (s == null || m.session != s.session || fromDevice != s.controller || m.seq <= s.lastSeq) return null
        if (!fresh(sentAt ?: m.at, now, CastProtocol.COMMAND_MAX_AGE_MS)) return null
        return s.copy(lastSeq = m.seq)
    }

    /**
     * Whether to tell the controller about [now] after [last] (null: nothing told yet), [lastAt] / [nowMs] when: on
     * every change of the phase or a track, on a jump, and as a heartbeat every [CastProtocol.HEARTBEAT_MS] while it
     * plays. Each state is a GateDrop send, so nothing more often.
     */
    fun shouldReport(last: CastSnapshot?, lastAt: Long, now: CastSnapshot, nowMs: Long): Boolean {
        if (last == null) return true
        if (now.phase != last.phase || now.audio != last.audio || now.subtitle != last.subtitle || now.tracks != last.tracks) return true
        if (now.durationMs > 0 && last.durationMs <= 0) return true
        val expected = if (last.phase == CastPhase.PLAYING) last.positionMs + (nowMs - lastAt) else last.positionMs
        if (abs(now.positionMs - expected) > JUMP_MS) return true
        return now.phase == CastPhase.PLAYING && nowMs - lastAt >= CastProtocol.HEARTBEAT_MS
    }

    /** The place after [CastCmd.SEEK_BY] from [position], within the video. */
    fun seekTarget(position: Long, delta: Long, duration: Long): Long {
        val to = (position + delta).coerceAtLeast(0)
        return if (duration > 0) to.coerceAtMost((duration - 1_000).coerceAtLeast(0)) else to
    }

    /** Server and devices disagree a little on the time: up to a minute in the future is still "now". */
    private fun fresh(at: Long, now: Long, max: Long): Boolean = now - at <= max && at - now <= 60_000
}
