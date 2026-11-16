package com.gatecontrol.android.remote

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import timber.log.Timber

/**
 * H.264 of the screen with MediaCodec (contract „Fernzugriff Stufe 5“ §2): an input surface for the VirtualDisplay
 * of MediaProjection, Baseline profile without B-frames, SPS/PPS in front of every key frame (a new viewer needs
 * nothing cached), a key frame every 2 s and on request, the last picture repeated after 500 ms (a still screen
 * keeps the stream alive at almost no cost). Every output becomes a frame of `gc-screen.v1` ([ScreenProtocol.frame]).
 * Nothing of a picture is logged.
 */
class ScreenEncoder(
    val width: Int,
    val height: Int,
    private val fps: Int,
    bitrate: Int,
    private val sink: Sink,
) {
    interface Sink {
        /** The codec string (`avc1.PPCCLL`) is known: send `{t:'config'}` before the first frame. */
        fun onConfig(codec: String, width: Int, height: Int)

        /** One frame of `gc-screen.v1` (head + access unit). */
        fun onFrame(frame: ByteArray, key: Boolean)

        fun onError()
    }

    private val thread = HandlerThread("gc-screen-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    @Volatile private var csd: ByteArray? = null
    @Volatile private var released = false
    @Volatile var bitrate: Int = bitrate.coerceIn(ScreenProtocol.MIN_BITRATE, ScreenProtocol.MAX_BITRATE)
        private set

    val surface: Surface

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, this@ScreenEncoder.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ScreenProtocol.KEY_INTERVAL_S)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                try {
                    val buf = c.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(bytes, 0, info.size)
                        output(bytes, info)
                    }
                    c.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    // released while a buffer came: nothing to do
                }
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                Timber.w("Fernzugriff: encoder error (%s)", e.diagnosticInfo.take(60))
                if (!released) sink.onError()
            }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = Unit
        }, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    private fun output(bytes: ByteArray, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            csd = bytes
            ScreenProtocol.codecString(bytes)?.let { sink.onConfig(it, width, height) }
            return
        }
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        // an encoder that ignores KEY_PREPEND_HEADER_TO_SYNC_FRAMES: SPS/PPS in front of the key frame here
        val head = csd
        val unit = if (key && head != null && !ScreenProtocol.hasSps(bytes)) head + bytes else bytes
        if (unit.size + ScreenProtocol.HEADER > ScreenProtocol.MAX_FRAME) {
            // too large for the relay: skip it, the next key frame comes on request
            requestKeyframe()
            return
        }
        sink.onFrame(ScreenProtocol.frame(key, info.presentationTimeUs, unit), key)
    }

    fun requestKeyframe() {
        if (released) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** The relay's suggestion ({t:'rate'}) or a backlog: a lower bitrate, never above the start value. */
    fun lowerBitrate(to: Int) {
        val next = to.coerceIn(ScreenProtocol.MIN_BITRATE, bitrate)
        if (next == bitrate || released) return
        bitrate = next
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, next) }) }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
        thread.quitSafely()
    }
}
