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
    /** The receiver's volume 0–100 as it told (or as set here a moment ago); null: it does not tell (no volume control). */
    val volume: Int? = null,
    val muted: Boolean = false,
    /** When the volume was changed here last: the receiver's states keep their old volume a moment longer. */
    val volumeSetMs: Long = 0,
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
        // a change here a moment ago wins over a state that was on its way already
        val held = s.volumeSetMs > 0 && now - s.volumeSetMs < VOLUME_HOLD_MS
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
            volume = if (held) s.volume else m.volume ?: s.volume,
            muted = if (held) s.muted else m.muted ?: s.muted,
        )
    }

    /** A change of the volume here wins over the receiver's states for this long (they may have left before it arrived). */
    const val VOLUME_HOLD_MS = 3_000L

    /** The step of the phone's volume keys. */
    const val VOLUME_STEP = 5

    /** The volume can be changed: the session runs and the receiver tells its volume (an older one does not). */
    fun hasVolume(s: CastRemoteState): Boolean = s.volume != null && !s.phase.final && s.phase != CastRemotePhase.CONNECTING

    /**
     * The volume shown at once for a key ([delta] steps up or down, louder also ends muting) or the slider / mute
     * button ([volume], [muted]); null when there is no volume control now. The command follows ([command] with
     * [CastCmd.VOLUME]).
     */
    fun setVolume(s: CastRemoteState, now: Long, delta: Int? = null, volume: Int? = null, muted: Boolean? = null): CastRemoteState? {
        if (!hasVolume(s)) return null
        val current = s.volume ?: return null
        val nextVolume = when {
            delta != null -> (current + delta).coerceIn(0, 100)
            volume != null -> volume.coerceIn(0, 100)
            else -> current
        }
        val nextMuted = when {
            muted != null -> muted
            delta != null && delta > 0 -> false
            else -> s.muted
        }
        return s.copy(volume = nextVolume, muted = nextMuted, volumeSetMs = now)
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
    fun command(
        s: CastRemoteState,
        cmd: CastCmd,
        now: Long,
        positionMs: Long? = null,
        deltaMs: Long? = null,
        track: String? = null,
        volume: Int? = null,
        muted: Boolean? = null,
    ): Pair<CastMessage.Command, CastRemoteState>? {
        if (s.phase.final) return null
        if (s.phase == CastRemotePhase.CONNECTING && cmd != CastCmd.STOP && cmd != CastCmd.STATUS) return null
        if (cmd == CastCmd.VOLUME && volume == null && muted == null) return null
        val c = if (cmd == CastCmd.VOLUME) {
            CastMessage.Command(s.session, s.nextSeq, now, cmd, volume = volume?.coerceIn(0, 100), muted = muted)
        } else {
            CastMessage.Command(s.session, s.nextSeq, now, cmd, positionMs, deltaMs, track)
        }
        val here = position(s, now)
        val shown = when (cmd) {
            CastCmd.PAUSE -> s.copy(phase = CastRemotePhase.PAUSED, positionMs = here, sinceMs = now)
            CastCmd.PLAY -> s.copy(phase = CastRemotePhase.PLAYING, positionMs = here, sinceMs = now)
            CastCmd.SEEK -> s.copy(positionMs = clamp(positionMs ?: here, s.durationMs), sinceMs = now)
            CastCmd.SEEK_BY -> s.copy(positionMs = clamp(here + (deltaMs ?: 0), s.durationMs), sinceMs = now)
            CastCmd.AUDIO -> s.copy(audio = s.audio.map { it.copy(selected = it.id == track) })
            CastCmd.SUBTITLE -> s.copy(subtitles = s.subtitles.map { it.copy(selected = it.id == track) })
            CastCmd.VOLUME -> s.copy(volume = c.volume ?: s.volume, muted = c.muted ?: s.muted, volumeSetMs = now)
            CastCmd.STOP -> s.copy(phase = CastRemotePhase.STOPPED, positionMs = here, sinceMs = now)
            CastCmd.STATUS -> s
        }
        return c to shown.copy(nextSeq = s.nextSeq + 1)
    }

    private fun clamp(p: Long, duration: Long): Long = if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
}
