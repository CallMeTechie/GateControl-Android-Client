package com.gatecontrol.android.media

import android.content.Context
import android.media.MediaCodecList
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * What [MediaHub] needs to know about the app it runs in (GateControl TV, the phone app): how it is called in
 * Jellyfin's device list, what it plays itself, the quality setting and its words. Bound by each app.
 */
interface MediaDevice {
    /** The client in Jellyfin's devices ("GateControl TV", "GateControl"): one per app, not fixed for all. */
    val clientName: String

    /** This device's name ("Wohnzimmer", the model). */
    val deviceName: String

    /** The app's version. */
    val version: String

    /** "Qualität unterwegs": automatic by bandwidth, the original, or a fixed step. */
    val quality: StateFlow<MediaQualitySetting>

    fun setQuality(value: MediaQualitySetting)

    /** What this device plays itself (the device profile Jellyfin decides with). Asked when a video opens. */
    fun caps(): JellyfinDeviceCaps

    /** "Staffel 2" in the app's language, for a share's seasons. */
    fun seasonName(season: Int): String
}

/** The device profile of a phone or tablet from its own decoders: nothing goes on to an AV receiver. */
object MediaDeviceCaps {
    const val MIME_HEVC = "video/hevc"
    const val MIME_AV1 = "video/av01"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"
    const val MIME_AC3 = "audio/ac3"
    const val MIME_EAC3 = "audio/eac3"

    /**
     * The profile from the MIME types of the decoders found ([decoders]) and whether the screen shows HDR. DTS and
     * TrueHD are never in it (no passthrough on a phone): Jellyfin converts them to AAC. Dolby Digital (Plus) only
     * where a decoder for it is, else it is converted too. Pure, unit-tested.
     */
    fun fromDecoders(
        decoders: Collection<String>,
        hdrScreen: Boolean,
        name: String,
        maxAudioChannels: Int = 6,
    ): JellyfinDeviceCaps {
        val types = decoders.map { it.lowercase(Locale.ROOT) }.toSet()
        return JellyfinDeviceCaps(
            passthrough = emptySet(),
            maxAudioChannels = maxAudioChannels.coerceIn(2, 8),
            hevc = MIME_HEVC in types,
            av1 = MIME_AV1 in types,
            vp9 = MIME_VP9 in types,
            hdr = hdrScreen,
            ac3 = MIME_AC3 in types,
            eac3 = MIME_EAC3 in types,
            name = name,
        )
    }

    /** This phone's profile: its decoders (MediaCodecList) and its screen. */
    fun phone(context: Context, name: String, maxAudioChannels: Int = 6): JellyfinDeviceCaps {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }.flatMap { it.supportedTypes.asList() }
        }.getOrDefault(emptyList())
        val hdr = runCatching { context.resources.configuration.isScreenHdr }.getOrDefault(false)
        return fromDecoders(decoders, hdr, name, maxAudioChannels)
    }
}
