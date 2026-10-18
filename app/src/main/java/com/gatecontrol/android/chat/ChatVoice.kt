package com.gatecontrol.android.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A finished voice message: the file (AAC in MP4, `audio/mp4`), its length and waveform. */
data class VoiceTake(val file: File, val mime: String, val durationMs: Long, val waveform: List<Int>)

/**
 * Records one voice message with [MediaRecorder] (AAC in an MP4 container,
 * 32 kHz mono): stops by itself at the server's maximum length. The
 * waveform is the recorder's amplitude sampled while recording, reduced
 * to ≤ 64 values 0…255 (README "Sending").
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val amplitudes = ArrayList<Int>()
    private var sampler: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Current loudness 0…1 (for the recording animation). */
    val level: StateFlow<Float> = _level.asStateFlow()

    val isRecording: Boolean get() = recorder != null

    /** Starts recording into [target]; [maxMs] ends it by itself ([onMaxReached] runs). */
    fun start(target: File, maxMs: Long, onMaxReached: () -> Unit): Boolean {
        stopQuietly()
        return try {
            val r = MediaRecorder(context)
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(32_000)
            r.setAudioEncodingBitRate(48_000)
            r.setMaxDuration(maxMs.toInt())
            r.setOutputFile(target.path)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxReached()
            }
            r.prepare()
            r.start()
            recorder = r
            file = target
            startedAt = System.currentTimeMillis()
            amplitudes.clear()
            sampler = scope.launch {
                while (isActive) {
                    delay(SAMPLE_MS)
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    amplitudes += amp
                    _level.value = (amp / 32767f).coerceIn(0f, 1f)
                    _elapsedMs.value = System.currentTimeMillis() - startedAt
                }
            }
            true
        } catch (e: Exception) {
            Timber.w("Chats: recording not started (%s)", e.javaClass.simpleName)
            stopQuietly()
            target.delete()
            false
        }
    }

    /** Stops and returns the take; null when it was too short or failed. */
    fun finish(): VoiceTake? {
        val r = recorder ?: return null
        val f = file
        val duration = System.currentTimeMillis() - startedAt
        val ok = runCatching { r.stop() }.isSuccess
        release()
        if (!ok || f == null || !f.isFile || duration < MIN_MS) {
            f?.delete()
            return null
        }
        return VoiceTake(f, MIME, duration, VoiceWave.reduce(amplitudes, VoiceWave.MAX_BARS))
    }

    /** Stops and throws the take away (swiped to cancel). */
    fun cancel() {
        val f = file
        stopQuietly()
        f?.delete()
    }

    private fun stopQuietly() {
        runCatching { recorder?.stop() }
        release()
    }

    private fun release() {
        sampler?.cancel()
        sampler = null
        runCatching { recorder?.release() }
        recorder = null
        file = null
        _elapsedMs.value = 0L
        _level.value = 0f
    }

    companion object {
        const val MIME = "audio/mp4"
        private const val SAMPLE_MS = 100L
        const val MIN_MS = 700L
    }
}

/** Waveform of a voice message. Pure, unit-tested. */
object VoiceWave {
    const val MAX_BARS = 64

    /** Amplitudes (0…32767) → at most [bars] values 0…255 (peak per bucket, scaled to the loudest). */
    fun reduce(amplitudes: List<Int>, bars: Int = MAX_BARS): List<Int> {
        if (amplitudes.isEmpty() || bars <= 0) return emptyList()
        val n = minOf(bars, amplitudes.size)
        val buckets = (0 until n).map { i ->
            val from = i * amplitudes.size / n
            val to = max(from + 1, (i + 1) * amplitudes.size / n)
            amplitudes.subList(from, to).maxOrNull() ?: 0
        }
        val peak = buckets.maxOrNull()?.takeIf { it > 0 } ?: return List(n) { 0 }
        return buckets.map { (it * 255.0 / peak).roundToInt().coerceIn(0, 255) }
    }

    /** Bars to draw for [waveform] (empty: a flat default), resampled to [count]. */
    fun bars(waveform: List<Int>, count: Int): List<Float> {
        if (count <= 0) return emptyList()
        if (waveform.isEmpty()) return List(count) { 0.25f }
        return (0 until count).map { i ->
            val v = waveform[(i * waveform.size / count).coerceIn(0, waveform.size - 1)]
            (v / 255f).coerceIn(0.08f, 1f)
        }
    }

    /** `m:ss` of [ms]. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/** Plays one voice message at a time (seekable); the UI observes [state]. */
class VoicePlayer {
    data class State(val key: String? = null, val playing: Boolean = false, val positionMs: Long = 0L, val durationMs: Long = 0L)

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Plays [file] under [key]; the same key toggles pause / resume. */
    fun toggle(key: String, file: File) {
        val cur = _state.value
        val p = player
        if (cur.key == key && p != null) {
            if (p.isPlaying) {
                p.pause()
                _state.value = cur.copy(playing = false)
            } else {
                p.start()
                _state.value = cur.copy(playing = true)
                tick()
            }
            return
        }
        stop()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { _state.value = _state.value.copy(playing = false, positionMs = 0L) }
            mp.prepare()
            if (cur.key == key && cur.positionMs > 0) mp.seekTo(cur.positionMs.toInt())
            mp.start()
            player = mp
            _state.value = State(key, true, mp.currentPosition.toLong(), mp.duration.toLong().coerceAtLeast(0))
            tick()
        } catch (e: Exception) {
            Timber.w("Chats: voice message not played (%s)", e.javaClass.simpleName)
            stop()
        }
    }

    /** Seek within the message [key] (0…1); remembered when it is not playing yet. */
    fun seek(key: String, fraction: Float, durationMs: Long) {
        val f = fraction.coerceIn(0f, 1f)
        val p = player
        if (_state.value.key == key && p != null) {
            val pos = (p.duration * f).toInt()
            p.seekTo(pos)
            _state.value = _state.value.copy(positionMs = pos.toLong())
        } else {
            stop()
            _state.value = State(key, false, (durationMs * f).toLong(), durationMs)
        }
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                if (!p.isPlaying) break
                _state.value = _state.value.copy(positionMs = p.currentPosition.toLong())
                delay(100)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _state.value = State()
    }
}
