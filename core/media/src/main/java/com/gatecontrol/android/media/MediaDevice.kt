package com.gatecontrol.android.media

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.jellyfin.JellyfinVideoCaps
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

/**
 * One decoder of the device as [MediaDeviceCaps] needs it: its MIME type, the (profile, level) pairs it reports
 * (`MediaCodecInfo.CodecProfileLevel`) and the largest picture it decodes (null: not known).
 */
data class MediaDecoder(
    val mime: String,
    val profileLevels: List<Pair<Int, Int>> = emptyList(),
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    /** A hardware decoder: a video codec counts by these (a software one only when there is none, up to 1080p). */
    val hardware: Boolean = true,
)

/** The device profile of a phone or tablet from its own decoders: nothing goes on to an AV receiver. */
object MediaDeviceCaps {
    const val MIME_AVC = "video/avc"
    const val MIME_HEVC = "video/hevc"
    const val MIME_AV1 = "video/av01"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"
    const val MIME_MPEG4 = "video/mp4v-es"
    const val MIME_AC3 = "audio/ac3"
    const val MIME_EAC3 = "audio/eac3"
    const val MIME_MP2 = "audio/mpeg-l2"
    const val MIME_ALAC = "audio/alac"

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
    ): JellyfinDeviceCaps = fromCodecs(decoders.map { MediaDecoder(it) }, hdrScreen, name, maxAudioChannels, mimeOnly = true)

    /**
     * The profile from the decoders with their profiles, levels and sizes: a video above what they report (H.264 with
     * 10 bits, HEVC Main 10 without a decoder for it, a level or size too high) is not played directly. [mimeOnly]:
     * only the MIME types are known (no limits beyond the codec; MPEG-4, MP2 and ALAC as on the TV). Pure, unit-tested.
     */
    fun fromCodecs(
        decoders: List<MediaDecoder>,
        hdrScreen: Boolean,
        name: String,
        maxAudioChannels: Int = 6,
        mimeOnly: Boolean = false,
    ): JellyfinDeviceCaps {
        // a video codec by its hardware decoders; software ones (HEVC 10 bits, AV1 on many phones) are too slow above
        // 1080p, so only up to that when there is no hardware one
        val by = decoders.groupBy { it.mime.lowercase(Locale.ROOT) }.mapValues { (mime, list) ->
            if (!mime.startsWith("video/")) list
            else list.filter { it.hardware }.ifEmpty { list.map { d -> d.copy(maxWidth = minOf(d.maxWidth ?: SOFTWARE_SIDE, SOFTWARE_SIDE), maxHeight = minOf(d.maxHeight ?: SOFTWARE_SIDE, SOFTWARE_SIDE)) } }
        }
        fun has(mime: String) = by.containsKey(mime)
        fun profiles(mime: String) = by[mime].orEmpty().flatMap { it.profileLevels }
        fun size(mime: String, c: JellyfinVideoCaps): JellyfinVideoCaps {
            val list = by[mime].orEmpty()
            val w = list.mapNotNull { it.maxWidth }.maxOrNull()
            val h = list.mapNotNull { it.maxHeight }.maxOrNull()
            // the larger side either way: a decoder of 3840 x 2160 also takes 2160 x 3840
            val side = listOfNotNull(w, h).maxOrNull()
            return c.copy(maxWidth = side?.takeIf { it > 0 }, maxHeight = side?.takeIf { it > 0 })
        }
        fun tenBit(mime: String, ten: Set<Int>, unknown: Boolean): Boolean {
            val p = profiles(mime)
            return if (p.isEmpty()) unknown else p.any { it.first in ten }
        }
        val avc = size(
            MIME_AVC,
            JellyfinVideoCaps(maxLevel = profiles(MIME_AVC).mapNotNull { avcLevel(it.second) }.maxOrNull(), tenBit = tenBit(MIME_AVC, AVC_10BIT, unknown = false)),
        )
        val hevc = size(
            MIME_HEVC,
            JellyfinVideoCaps(maxLevel = profiles(MIME_HEVC).mapNotNull { hevcLevel(it.second) }.maxOrNull(), tenBit = tenBit(MIME_HEVC, HEVC_10BIT, unknown = true)),
        )
        val av1 = size(MIME_AV1, JellyfinVideoCaps(tenBit = tenBit(MIME_AV1, AV1_10BIT, unknown = true)))
        val vp9 = size(MIME_VP9, JellyfinVideoCaps(tenBit = tenBit(MIME_VP9, VP9_10BIT, unknown = true)))
        return JellyfinDeviceCaps(
            passthrough = emptySet(),
            maxAudioChannels = maxAudioChannels.coerceIn(2, 8),
            hevc = has(MIME_HEVC),
            av1 = has(MIME_AV1),
            vp9 = has(MIME_VP9),
            hdr = hdrScreen,
            ac3 = has(MIME_AC3),
            eac3 = has(MIME_EAC3),
            name = name,
            h264 = avc,
            hevcCaps = hevc,
            av1Caps = av1,
            vp9Caps = vp9,
            mpeg4 = mimeOnly || has(MIME_MPEG4),
            mp2 = mimeOnly || has(MIME_MP2),
            alac = mimeOnly || has(MIME_ALAC),
        )
    }

    /** The largest side a software video decoder is trusted with. */
    const val SOFTWARE_SIDE = 1920

    private val AVC_10BIT = setOf(CodecProfileLevel.AVCProfileHigh10)
    private val HEVC_10BIT = setOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus)
    private val AV1_10BIT = setOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus)
    private val VP9_10BIT = setOf(
        CodecProfileLevel.VP9Profile2,
        CodecProfileLevel.VP9Profile3,
        CodecProfileLevel.VP9Profile2HDR,
        CodecProfileLevel.VP9Profile3HDR,
        CodecProfileLevel.VP9Profile2HDR10Plus,
        CodecProfileLevel.VP9Profile3HDR10Plus,
    )

    /** Android's H.264 level → Jellyfin's (4.1 → 41); null: unknown. */
    fun avcLevel(level: Int): Int? = when (level) {
        CodecProfileLevel.AVCLevel1, CodecProfileLevel.AVCLevel1b -> 10
        CodecProfileLevel.AVCLevel11 -> 11
        CodecProfileLevel.AVCLevel12 -> 12
        CodecProfileLevel.AVCLevel13 -> 13
        CodecProfileLevel.AVCLevel2 -> 20
        CodecProfileLevel.AVCLevel21 -> 21
        CodecProfileLevel.AVCLevel22 -> 22
        CodecProfileLevel.AVCLevel3 -> 30
        CodecProfileLevel.AVCLevel31 -> 31
        CodecProfileLevel.AVCLevel32 -> 32
        CodecProfileLevel.AVCLevel4 -> 40
        CodecProfileLevel.AVCLevel41 -> 41
        CodecProfileLevel.AVCLevel42 -> 42
        CodecProfileLevel.AVCLevel5 -> 50
        CodecProfileLevel.AVCLevel51 -> 51
        CodecProfileLevel.AVCLevel52 -> 52
        CodecProfileLevel.AVCLevel6 -> 60
        CodecProfileLevel.AVCLevel61 -> 61
        CodecProfileLevel.AVCLevel62 -> 62
        else -> null
    }

    /** Android's HEVC level (either tier) → Jellyfin's (level × 30: 5.1 → 153); null: unknown. */
    fun hevcLevel(level: Int): Int? = when (level) {
        CodecProfileLevel.HEVCMainTierLevel1, CodecProfileLevel.HEVCHighTierLevel1 -> 30
        CodecProfileLevel.HEVCMainTierLevel2, CodecProfileLevel.HEVCHighTierLevel2 -> 60
        CodecProfileLevel.HEVCMainTierLevel21, CodecProfileLevel.HEVCHighTierLevel21 -> 63
        CodecProfileLevel.HEVCMainTierLevel3, CodecProfileLevel.HEVCHighTierLevel3 -> 90
        CodecProfileLevel.HEVCMainTierLevel31, CodecProfileLevel.HEVCHighTierLevel31 -> 93
        CodecProfileLevel.HEVCMainTierLevel4, CodecProfileLevel.HEVCHighTierLevel4 -> 120
        CodecProfileLevel.HEVCMainTierLevel41, CodecProfileLevel.HEVCHighTierLevel41 -> 123
        CodecProfileLevel.HEVCMainTierLevel5, CodecProfileLevel.HEVCHighTierLevel5 -> 150
        CodecProfileLevel.HEVCMainTierLevel51, CodecProfileLevel.HEVCHighTierLevel51 -> 153
        CodecProfileLevel.HEVCMainTierLevel52, CodecProfileLevel.HEVCHighTierLevel52 -> 156
        CodecProfileLevel.HEVCMainTierLevel6, CodecProfileLevel.HEVCHighTierLevel6 -> 180
        CodecProfileLevel.HEVCMainTierLevel61, CodecProfileLevel.HEVCHighTierLevel61 -> 183
        CodecProfileLevel.HEVCMainTierLevel62, CodecProfileLevel.HEVCHighTierLevel62 -> 186
        else -> null
    }

    /** This phone's profile: its decoders (MediaCodecList, with their profiles, levels and sizes) and its screen. */
    fun phone(context: Context, name: String, maxAudioChannels: Int = 6): JellyfinDeviceCaps {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }.flatMap { info ->
                info.supportedTypes.map { type ->
                    val c = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                    val video = c?.videoCapabilities
                    MediaDecoder(
                        mime = type,
                        profileLevels = c?.profileLevels?.map { it.profile to it.level }.orEmpty(),
                        maxWidth = video?.supportedWidths?.upper,
                        maxHeight = video?.supportedHeights?.upper,
                        hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android."),
                    )
                }
            }
        }.getOrDefault(emptyList())
        val hdr = runCatching { context.resources.configuration.isScreenHdr }.getOrDefault(false)
        return fromCodecs(decoders, hdr, name, maxAudioChannels)
    }
}
