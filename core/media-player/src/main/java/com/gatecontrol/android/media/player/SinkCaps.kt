package com.gatecontrol.android.media.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps

/**
 * The device profile of a TV stick at an AV receiver: what the HDMI sink takes as it is (DTS and Dolby go through),
 * the stick decodes Dolby Digital (Plus) itself. A phone's profile comes from its decoders
 * ([com.gatecontrol.android.media.MediaDeviceCaps.phone]).
 */
object SinkCaps {
    @OptIn(UnstableApi::class)
    fun of(context: Context, name: String = JellyfinDeviceCaps().name): JellyfinDeviceCaps {
        val audio = runCatching { AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null) }.getOrNull()
        val pass = buildSet {
            if (audio?.supportsEncoding(C.ENCODING_AC3) == true) add("ac3")
            if (audio?.supportsEncoding(C.ENCODING_E_AC3) == true) add("eac3")
            if (audio?.supportsEncoding(C.ENCODING_DTS) == true) add("dts")
            if (audio?.supportsEncoding(C.ENCODING_DOLBY_TRUEHD) == true) add("truehd")
        }
        val channels = audio?.maxChannelCount?.takeIf { it > 0 }?.coerceAtMost(8) ?: 6
        return JellyfinDeviceCaps(passthrough = pass, maxAudioChannels = channels.coerceAtLeast(2), name = name)
    }
}
