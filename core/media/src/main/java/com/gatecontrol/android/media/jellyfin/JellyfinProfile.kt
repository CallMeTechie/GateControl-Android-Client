package com.gatecontrol.android.media.jellyfin

import com.gatecontrol.android.media.MediaStream
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

/**
 * What a device's decoders take of one video codec: the highest level (Jellyfin's count: H.264 41 = 4.1, HEVC
 * 153 = 5.1; null: not known, no limit), 10 bits per colour and the largest picture. A file above it is not played
 * directly (Jellyfin packs it anew or converts it): otherwise the player waits forever for a first picture.
 */
data class JellyfinVideoCaps(
    val maxLevel: Int? = null,
    val tenBit: Boolean = true,
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
)

/**
 * What the device plays itself — the device profile Jellyfin decides with (direct or converted). The defaults are
 * GateControl TV's; a phone's come from its decoders ([com.gatecontrol.android.media.MediaDeviceCaps]).
 *
 * Fire TV Stick 4K Max: H.264, HEVC (also HDR), VP9 and AV1 in mkv, mp4 and ts; AAC, MP3, FLAC, Opus, Vorbis, Dolby
 * Digital (Plus) decoded on the stick. DTS and TrueHD only go to an AV receiver that takes them over HDMI
 * ([passthrough], from the sink's audio capabilities); without one Jellyfin converts the sound (to Dolby Digital 5.1).
 * Text subtitles come beside the video; picture subtitles (PGS, DVD) play in an mkv played directly and are burnt in
 * when converting.
 */
data class JellyfinDeviceCaps(
    /** Sound formats the HDMI sink takes as they are (`ac3`, `eac3`, `dts`, `truehd`). */
    val passthrough: Set<String> = emptySet(),
    val maxAudioChannels: Int = 6,
    val hevc: Boolean = true,
    val av1: Boolean = true,
    val vp9: Boolean = true,
    /** HDR on the screen: otherwise HDR video is converted (tone-mapped) on the NAS. */
    val hdr: Boolean = true,
    /**
     * Dolby Digital (Plus) decoded on the device itself. Always so on the Fire TV Stick; a phone only with a decoder
     * for it, else Jellyfin converts the sound (to AAC).
     */
    val ac3: Boolean = true,
    val eac3: Boolean = true,
    val name: String = "GateControl TV",
    /** H.264: 10 bits (High 10, "Hi10P") hardly any device decodes, so 8 bits unless a decoder says otherwise. */
    val h264: JellyfinVideoCaps = JellyfinVideoCaps(tenBit = false),
    val hevcCaps: JellyfinVideoCaps = JellyfinVideoCaps(),
    val av1Caps: JellyfinVideoCaps = JellyfinVideoCaps(),
    val vp9Caps: JellyfinVideoCaps = JellyfinVideoCaps(),
    /** MPEG-4 Part 2 (DivX, Xvid): only with a decoder for it. */
    val mpeg4: Boolean = true,
    /** MPEG audio layer II and Apple Lossless: only with a decoder for them (a phone often has none). */
    val mp2: Boolean = true,
    val alac: Boolean = true,
)

object JellyfinProfile {
    /** Sound the stick decodes itself. */
    val DECODED_AUDIO = listOf("aac", "mp3", "mp2", "flac", "opus", "vorbis", "ac3", "eac3", "alac", "pcm_s16le", "pcm_s24le")

    /** Sound that needs an AV receiver to go through. */
    val PASSTHROUGH_ONLY = setOf("dts", "dca", "truehd")

    private val TEXT_SUBTITLES = listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa", "ttml", "mov_text")
    private val PICTURE_SUBTITLES = listOf("pgssub", "pgs", "dvdsub", "dvbsub")

    /** The sound [caps]' device decodes itself: [DECODED_AUDIO] without Dolby Digital (Plus) where it has no decoder. */
    fun decodedAudio(caps: JellyfinDeviceCaps): List<String> =
        DECODED_AUDIO.filter { (it != "ac3" || caps.ac3) && (it != "eac3" || caps.eac3) && (it != "mp2" || caps.mp2) && (it != "alac" || caps.alac) }

    /** The sound codecs the device plays directly with [caps] (decoded, or passed on to the AV receiver). */
    fun directAudio(caps: JellyfinDeviceCaps): List<String> {
        val pass = caps.passthrough.map { it.lowercase(Locale.ROOT) }.filter { it in PASSTHROUGH_ONLY || it == "ac3" || it == "eac3" }
        val extra = if ("dts" in pass) pass + "dca" else pass
        return (decodedAudio(caps) + extra).distinct()
    }

    /** The sound Jellyfin converts to for [caps]: AAC, Dolby Digital (Plus) where the device plays it, DTS to a receiver. */
    fun convertedAudio(caps: JellyfinDeviceCaps): List<String> {
        val direct = directAudio(caps)
        return listOf("aac") + listOf("ac3", "eac3").filter { it in direct } + caps.passthrough.filter { it == "dts" }
    }

    /** The sound stream [plan] plays (the chosen one, else the file's default); null when it has none known. */
    fun soundOf(plan: com.gatecontrol.android.media.MediaPlan): com.gatecontrol.android.media.MediaStream? =
        plan.version.audio.firstOrNull { it.index == plan.audioIndex } ?: plan.version.audio.firstOrNull { it.index == plan.version.defaultAudio }

    /**
     * The sound of [plan] plays on [caps]' device as the server sends it: converted, or its codec is one the device
     * decodes (or passes to the AV receiver). Unknown sound counts as playing. Pure, unit-tested.
     */
    fun soundPlays(plan: com.gatecontrol.android.media.MediaPlan, caps: JellyfinDeviceCaps): Boolean {
        if (plan.method == com.gatecontrol.android.media.PlayMethod.TRANSCODE) return true
        val codec = soundOf(plan)?.codec?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return true
        return codec in directAudio(caps)
    }

    fun videoCodecs(caps: JellyfinDeviceCaps): List<String> =
        listOfNotNull("h264", if (caps.hevc) "hevc" else null, if (caps.vp9) "vp9" else null, if (caps.av1) "av1" else null, if (caps.mpeg4) "mpeg4" else null)

    /** `DeviceProfile` of `PlaybackInfo` for [caps] and [maxBitrate]. */
    fun build(caps: JellyfinDeviceCaps, maxBitrate: Long): JsonObject {
        val audio = directAudio(caps).joinToString(",")
        val video = videoCodecs(caps).joinToString(",")
        return JsonObject().apply {
            addProperty("Name", caps.name)
            addProperty("MaxStreamingBitrate", maxBitrate)
            addProperty("MaxStaticBitrate", maxBitrate)
            addProperty("MusicStreamingTranscodingBitrate", 384_000)
            add(
                "DirectPlayProfiles",
                JsonArray().apply {
                    add(direct("mkv,webm", video, audio))
                    add(direct("mp4,m4v,mov", video, audio))
                    add(direct("ts,mpegts,m2ts", video, audio))
                },
            )
            add(
                "TranscodingProfiles",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("Container", "ts")
                            addProperty("Type", "Video")
                            addProperty("Protocol", "hls")
                            // H.264: what the J3455's Quick Sync converts to without effort
                            addProperty("VideoCodec", "h264")
                            addProperty("AudioCodec", convertedAudio(caps).joinToString(","))
                            addProperty("Context", "Streaming")
                            addProperty("MaxAudioChannels", caps.maxAudioChannels.toString())
                            addProperty("MinSegments", 1)
                            addProperty("BreakOnNonKeyFrames", true)
                            addProperty("CopyTimestamps", false)
                        },
                    )
                },
            )
            add("ContainerProfiles", JsonArray())
            add(
                "CodecProfiles",
                JsonArray().apply {
                    // what the decoders take of each codec: profile, bits, level, size (unknown values pass)
                    add(video("h264", caps.h264, profiles = if (caps.h264.tenBit) H264_PROFILES + "high 10" else H264_PROFILES))
                    if (caps.hevc) add(video("hevc", caps.hevcCaps, profiles = if (caps.hevcCaps.tenBit) listOf("main", "main 10") else listOf("main")))
                    if (caps.av1) add(video("av1", caps.av1Caps, profiles = listOf("main")))
                    if (caps.vp9) add(video("vp9", caps.vp9Caps, profiles = null))
                    if (!caps.hdr) {
                        // an SDR screen: HDR video is tone-mapped on the NAS
                        add(
                            JsonObject().apply {
                                addProperty("Type", "Video")
                                add(
                                    "Conditions",
                                    JsonArray().apply {
                                        add(condition("EqualsAny", "VideoRangeType", "SDR"))
                                    },
                                )
                            },
                        )
                    }
                },
            )
            add(
                "SubtitleProfiles",
                JsonArray().apply {
                    for (f in TEXT_SUBTITLES) {
                        add(subtitle(f, "External"))
                        add(subtitle(f, "Embed"))
                    }
                    for (f in PICTURE_SUBTITLES) {
                        add(subtitle(f, "Embed"))
                        add(subtitle(f, "Encode"))
                    }
                },
            )
        }
    }

    /** H.264's 8-bit profiles as Jellyfin names them (High 10, 4:2:2 and 4:4:4 are not decoded on devices). */
    private val H264_PROFILES = listOf("high", "main", "baseline", "constrained baseline")

    /** The conditions of [codec] on [v]'s device. */
    private fun video(codec: String, v: JellyfinVideoCaps, profiles: List<String>?) = JsonObject().apply {
        addProperty("Type", "Video")
        addProperty("Codec", codec)
        add(
            "Conditions",
            JsonArray().apply {
                profiles?.let { add(condition("EqualsAny", "VideoProfile", it.joinToString("|"))) }
                add(condition("LessThanEqual", "VideoBitDepth", if (v.tenBit) "10" else "8"))
                v.maxLevel?.let { add(condition("LessThanEqual", "VideoLevel", it.toString())) }
                v.maxWidth?.let { add(condition("LessThanEqual", "Width", it.toString())) }
                v.maxHeight?.let { add(condition("LessThanEqual", "Height", it.toString())) }
            },
        )
    }

    /**
     * Why [video] does not play on [caps]' device as it is ("H.264 10-Bit (High 10)", "HEVC 10-Bit (Main 10)", a level
     * or size too high, a codec without decoder); null: it plays, or nothing is known against it. The same rules the
     * device profile gives Jellyfin, checked here before asking: such a file is converted at once, never copied.
     */
    fun videoProblem(video: MediaStream?, caps: JellyfinDeviceCaps): String? {
        val v = video ?: return null
        val codec = v.codec?.lowercase(Locale.ROOT) ?: return null
        val profile = v.profile?.lowercase(Locale.ROOT)
        val bits = v.bitDepth
        val name = when (codec) {
            "h264", "avc" -> "H.264"
            "hevc", "h265" -> "HEVC"
            "av1" -> "AV1"
            "vp9" -> "VP9"
            else -> codec.uppercase(Locale.ROOT)
        }
        fun label(what: String) = listOfNotNull(name, what.takeIf { it.isNotEmpty() }).joinToString(" ") + (v.profile?.let { " ($it)" } ?: "")
        val c = when (codec) {
            "h264", "avc" -> caps.h264
            "hevc", "h265" -> if (caps.hevc) caps.hevcCaps else return label("")
            "av1" -> if (caps.av1) caps.av1Caps else return label("")
            "vp9" -> if (caps.vp9) caps.vp9Caps else return label("")
            "mpeg4" -> return if (caps.mpeg4) null else label("")
            else -> return null
        }
        val tooDeep = (bits != null && bits > (if (c.tenBit) 10 else 8)) ||
            (codec == "h264" || codec == "avc") && profile != null && (profile.contains("4:2:2") || profile.contains("4:4:4") || (!c.tenBit && profile.contains("10")))
        if (tooDeep) return label("${bits ?: 10}-Bit")
        val level = v.level
        if (level != null && c.maxLevel != null && level > c.maxLevel) return label("Level $level")
        val side = maxOf(v.width ?: 0, v.height ?: 0)
        val max = maxOf(c.maxWidth ?: 0, c.maxHeight ?: 0)
        if (max > 0 && side > max) return label("${v.height ?: side}p")
        return null
    }

    /** A short line of what [caps] decodes, for the app's log (no names or addresses). */
    fun describe(caps: JellyfinDeviceCaps): String {
        fun v(name: String, on: Boolean, c: JellyfinVideoCaps) =
            if (!on) "$name=no" else "$name=" + listOfNotNull(c.maxLevel?.let { "L$it" }, if (c.tenBit) "10bit" else "8bit", c.maxHeight?.let { "${it}p" }).joinToString("/")
        return listOf(
            v("h264", true, caps.h264),
            v("hevc", caps.hevc, caps.hevcCaps),
            v("av1", caps.av1, caps.av1Caps),
            v("vp9", caps.vp9, caps.vp9Caps),
            "audio=" + directAudio(caps).joinToString(","),
            "hdr=${caps.hdr}",
            "ch=${caps.maxAudioChannels}",
        ).joinToString(" ")
    }

    private fun direct(container: String, video: String, audio: String) = JsonObject().apply {
        addProperty("Container", container)
        addProperty("Type", "Video")
        addProperty("VideoCodec", video)
        addProperty("AudioCodec", audio)
    }

    private fun subtitle(format: String, method: String) = JsonObject().apply {
        addProperty("Format", format)
        addProperty("Method", method)
    }

    private fun condition(op: String, property: String, value: String) = JsonObject().apply {
        addProperty("Condition", op)
        addProperty("Property", property)
        addProperty("Value", value)
        addProperty("IsRequired", false)
    }
}
