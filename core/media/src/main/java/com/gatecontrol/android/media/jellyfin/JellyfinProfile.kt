package com.gatecontrol.android.media.jellyfin

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

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
        DECODED_AUDIO.filter { (it != "ac3" || caps.ac3) && (it != "eac3" || caps.eac3) }

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

    fun videoCodecs(caps: JellyfinDeviceCaps): List<String> =
        listOfNotNull("h264", if (caps.hevc) "hevc" else null, if (caps.vp9) "vp9" else null, if (caps.av1) "av1" else null, "mpeg4")

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
