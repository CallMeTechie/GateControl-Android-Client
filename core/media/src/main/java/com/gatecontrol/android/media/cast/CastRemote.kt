package com.gatecontrol.android.media.cast

/** How the phone (the controller) sees the playback on the other device. */
enum class CastRemotePhase {
    /** [CastMessage.Play] is out, no answer yet. */
    CONNECTING,
    LOADING,
    PLAYING,
    PAUSED,
    BUFFERING,
    ENDED,
    STOPPED,
    FAILED,

    /** No answer in time (the device is off, GateControl not open there or too old). */
    NO_ANSWER,
    ;

    /** Over: the remote closes, a new start is needed. */
    val final: Boolean get() = this == ENDED || this == STOPPED || this == FAILED || this == NO_ANSWER
}

/**
 * The controller's view of one session: what the receiver reported last and when it came here ([sinceMs], the
 * controller's own clock: the receiver's clock is never compared). Pure; [CastRemote] moves it.
 */
data class CastRemoteState(
    val session: String,
    val deviceId: String,
    val deviceName: String,
    val title: String,
    val phase: CastRemotePhase = CastRemotePhase.CONNECTING,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** When the last state arrived (or the session began). */
    val sinceMs: Long = 0,
    val startedMs: Long = 0,
    val audio: List<CastTrack> = emptyList(),
    val subtitles: List<CastTrack> = emptyList(),
    val error: String? = null,
    /** The receiver's last [CastMessage.State.seq]. */
    val lastSeq: Long = -1,
    /** Our own next [CastMessage.seq]. */
    val nextSeq: Long = 1,
)

/** The controller's state machine (pure, unit-tested). */
object CastRemote {

    fun start(session: String, deviceId: String, deviceName: String, title: String, positionMs: Long, now: Long) =
        CastRemoteState(session, deviceId, deviceName, title, CastRemotePhase.CONNECTING, positionMs.coerceAtLeast(0), sinceMs = now, startedMs = now)

    /**
     * A state that came in from [fromDevice]: taken when it is this session's, from its device and newer than the
     * last; otherwise [s] stays.
     */
    fun onState(s: CastRemoteState, m: CastMessage.State, fromDevice: String, now: Long): CastRemoteState {
        if (m.session != s.session || fromDevice != s.deviceId || m.seq <= s.lastSeq) return s
        if (s.phase.final) return s
        return s.copy(
            phase = phaseOf(m.phase),
            positionMs = m.positionMs,
            durationMs = if (m.durationMs > 0) m.durationMs else s.durationMs,
            sinceMs = now,
            audio = m.audio,
            subtitles = m.subtitles,
            error = m.error,
            lastSeq = m.seq,
            deviceName = m.device ?: s.deviceName,
            title = m.title ?: s.title,
        )
    }

    private fun phaseOf(p: CastPhase) = when (p) {
        CastPhase.LOADING -> CastRemotePhase.LOADING
        CastPhase.PLAYING -> CastRemotePhase.PLAYING
        CastPhase.PAUSED -> CastRemotePhase.PAUSED
        CastPhase.BUFFERING -> CastRemotePhase.BUFFERING
        CastPhase.ENDED -> CastRemotePhase.ENDED
        CastPhase.STOPPED -> CastRemotePhase.STOPPED
        CastPhase.FAILED -> CastRemotePhase.FAILED
    }

    /** Where it is now: playing it went on since the last state (up to the end), otherwise it stands. */
    fun position(s: CastRemoteState, now: Long): Long {
        if (s.phase != CastRemotePhase.PLAYING) return s.positionMs
        val p = s.positionMs + (now - s.sinceMs).coerceAtLeast(0)
        return if (s.durationMs > 0) p.coerceAtMost(s.durationMs) else p
    }

    /** Time passing: no first answer in [CastProtocol.ANSWER_TIMEOUT_MS], or none for [CastProtocol.STALE_MS]. */
    fun tick(s: CastRemoteState, now: Long): CastRemoteState = when {
        s.phase.final -> s
        s.phase == CastRemotePhase.CONNECTING && now - s.startedMs >= CastProtocol.ANSWER_TIMEOUT_MS -> s.copy(phase = CastRemotePhase.NO_ANSWER)
        s.phase != CastRemotePhase.CONNECTING && now - s.sinceMs >= CastProtocol.STALE_MS -> s.copy(phase = CastRemotePhase.NO_ANSWER, positionMs = position(s, now))
        else -> s
    }

    /**
     * The command for a key of the remote and the state shown until the receiver answers (pause shows paused at
     * once, a jump the new place); null when it makes no sense now (over, or not started yet).
     */
    fun command(s: CastRemoteState, cmd: CastCmd, now: Long, positionMs: Long? = null, deltaMs: Long? = null, track: String? = null): Pair<CastMessage.Command, CastRemoteState>? {
        if (s.phase.final) return null
        if (s.phase == CastRemotePhase.CONNECTING && cmd != CastCmd.STOP && cmd != CastCmd.STATUS) return null
        val c = CastMessage.Command(s.session, s.nextSeq, now, cmd, positionMs, deltaMs, track)
        val here = position(s, now)
        val shown = when (cmd) {
            CastCmd.PAUSE -> s.copy(phase = CastRemotePhase.PAUSED, positionMs = here, sinceMs = now)
            CastCmd.PLAY -> s.copy(phase = CastRemotePhase.PLAYING, positionMs = here, sinceMs = now)
            CastCmd.SEEK -> s.copy(positionMs = clamp(positionMs ?: here, s.durationMs), sinceMs = now)
            CastCmd.SEEK_BY -> s.copy(positionMs = clamp(here + (deltaMs ?: 0), s.durationMs), sinceMs = now)
            CastCmd.AUDIO -> s.copy(audio = s.audio.map { it.copy(selected = it.id == track) })
            CastCmd.SUBTITLE -> s.copy(subtitles = s.subtitles.map { it.copy(selected = it.id == track) })
            CastCmd.STOP -> s.copy(phase = CastRemotePhase.STOPPED, positionMs = here, sinceMs = now)
            CastCmd.STATUS -> s
        }
        return c to shown.copy(nextSeq = s.nextSeq + 1)
    }

    private fun clamp(p: Long, duration: Long): Long = if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
}
