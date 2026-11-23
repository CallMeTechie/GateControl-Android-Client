package com.gatecontrol.android.media

import java.util.Locale

/**
 * The quality of the player (decision 10): "Automatisch" — at home always the original, away the original only when
 * the measured bandwidth carries it, else the NAS converts to the step that goes through without stalling — or a
 * fixed step up to "immer Original".
 */
enum class MediaQualitySetting(val wire: String, val bps: Long?) {
    AUTO("auto", null),
    ORIGINAL("original", MediaQuality.ORIGINAL_BPS),
    MBIT_20("20", 20_000_000),
    MBIT_12("12", 12_000_000),
    MBIT_8("8", 8_000_000),
    MBIT_4("4", 4_000_000),
    MBIT_2("2", 2_000_000),
    ;

    companion object {
        fun fromWire(value: String?): MediaQualitySetting = entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

/** What the detail page offers: the original and, when it does not fit, the step the NAS converts to. */
data class MediaQualityOffer(
    /** The original's bit rate (null: unknown). */
    val originalBps: Long?,
    /** The original fits the measured line (or it is home / unknown). */
    val originalFits: Boolean,
    /** The converted step for this line; null when the original fits or the source cannot convert. */
    val adaptedBps: Long?,
    /** What "Automatisch" picks: the original or [adaptedBps]. */
    val suggestAdapted: Boolean,
)

/** Pure, unit-tested. */
object MediaQuality {
    /** "No limit" for the server: it sends the original whenever the TV can play it. */
    const val ORIGINAL_BPS = 200_000_000L

    /** Away without a measurement: a careful start. */
    const val UNMEASURED_BPS = 8_000_000L

    /** The steps the NAS converts to (as the mockup: 1080p · 8 Mbit/s for 18 Mbit/s measured). */
    val LADDER = listOf(20_000_000L, 12_000_000L, 8_000_000L, 4_000_000L, 2_000_000L, 1_000_000L)

    /** Of the measured bandwidth this share is used: the line of a hotel varies, the audio and the overhead come on top. */
    const val HEADROOM = 0.6

    /** The highest step of [LADDER] that fits [measuredBps] with [HEADROOM]; the lowest when none does. */
    fun step(measuredBps: Long): Long {
        val usable = (measuredBps * HEADROOM).toLong()
        return LADDER.firstOrNull { it <= usable } ?: LADDER.last()
    }

    /** The original of [originalBps] goes through [measuredBps] (with [HEADROOM]); unknown bit rate: assumed not. */
    fun fits(originalBps: Long?, measuredBps: Long): Boolean =
        originalBps != null && originalBps > 0 && originalBps <= (measuredBps * HEADROOM).toLong()

    /**
     * The `MaxStreamingBitrate` to ask for: [setting] fixed, else at home (or [alwaysOriginal], or a source that cannot
     * convert) the original; away the original when it fits [measuredBps], else [step]. Away without a measurement:
     * [UNMEASURED_BPS] unless the original is known to be smaller.
     */
    fun cap(
        setting: MediaQualitySetting,
        atHome: Boolean,
        measuredBps: Long?,
        originalBps: Long?,
        alwaysOriginal: Boolean = false,
        canConvert: Boolean = true,
    ): Long {
        if (!canConvert || alwaysOriginal) return ORIGINAL_BPS
        setting.bps?.let { return it }
        if (atHome) return ORIGINAL_BPS
        if (measuredBps == null || measuredBps <= 0) {
            return if (originalBps != null && originalBps in 1..UNMEASURED_BPS) ORIGINAL_BPS else UNMEASURED_BPS
        }
        return if (fits(originalBps, measuredBps)) ORIGINAL_BPS else step(measuredBps)
    }

    /** What the detail page shows for a title of [originalBps] on this line (mockup 8). */
    fun offer(atHome: Boolean, measuredBps: Long?, originalBps: Long?, alwaysOriginal: Boolean = false, canConvert: Boolean = true): MediaQualityOffer {
        if (!canConvert) return MediaQualityOffer(originalBps, originalFits = true, adaptedBps = null, suggestAdapted = false)
        if (atHome) return MediaQualityOffer(originalBps, originalFits = true, adaptedBps = null, suggestAdapted = false)
        if (measuredBps == null || measuredBps <= 0) {
            val small = originalBps != null && originalBps in 1..UNMEASURED_BPS
            val adapted = if (small) null else UNMEASURED_BPS
            return MediaQualityOffer(originalBps, originalFits = small, adaptedBps = adapted, suggestAdapted = adapted != null && !alwaysOriginal)
        }
        val fits = fits(originalBps, measuredBps)
        val adapted = if (fits) null else step(measuredBps)
        return MediaQualityOffer(originalBps, fits, adapted, suggestAdapted = adapted != null && !alwaysOriginal)
    }

    /** "18 Mbit/s" / "2,5 Mbit/s" / "800 kbit/s". */
    fun label(bps: Long, locale: Locale = Locale.GERMANY): String = when {
        bps >= 10_000_000 -> "${bps / 1_000_000} Mbit/s"
        bps >= 1_000_000 -> {
            val tenths = bps / 100_000
            if (tenths % 10 == 0L) "${tenths / 10} Mbit/s" else String.format(locale, "%.1f Mbit/s", tenths / 10.0)
        }
        else -> "${bps / 1_000} kbit/s"
    }

    /** The picture a converting step gives (as the server scales by bit rate): "1080p" from 6 Mbit/s, "720p" from 3. */
    fun resolution(bps: Long): String = when {
        bps >= 6_000_000 -> "1080p"
        bps >= 3_000_000 -> "720p"
        else -> "480p"
    }

    /** The picture of a video stream: "4K HDR", "1080p", "720p". */
    fun videoLabel(video: MediaStream?): String? {
        val h = video?.height ?: return null
        val w = video.width ?: 0
        val res = when {
            h >= 1600 || w >= 3200 -> "4K"
            h >= 1000 || w >= 1800 -> "1080p"
            h >= 700 || w >= 1200 -> "720p"
            h > 0 -> "${h}p"
            else -> return null
        }
        val hdr = hdrLabel(video)
        return if (hdr != null) "$res $hdr" else res
    }

    /** "HDR10", "HDR10+", "Dolby Vision", "HLG", "HDR"; null for SDR. */
    fun hdrLabel(video: MediaStream?): String? {
        val type = video?.videoRangeType?.uppercase(Locale.ROOT)
        return when {
            type == null || type == "SDR" || type == "UNKNOWN" -> if (video?.videoRange.equals("HDR", ignoreCase = true)) "HDR" else null
            type.startsWith("DOVI") -> "Dolby Vision"
            type == "HDR10PLUS" -> "HDR10+"
            type == "HDR10" -> "HDR10"
            type == "HLG" -> "HLG"
            else -> "HDR"
        }
    }
}
