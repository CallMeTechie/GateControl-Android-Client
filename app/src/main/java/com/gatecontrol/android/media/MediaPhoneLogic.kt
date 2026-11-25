package com.gatecontrol.android.media

import android.media.AudioDeviceInfo
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import java.util.Locale

/** Small rules of the phone's media pages, kept apart from the composables. Pure, unit-tested. */
object MediaPhoneLogic {

    /** Question 28: on mobile data at most 3 Mbit/s unless the person changes it. */
    const val DEFAULT_MOBILE_CAP_BPS = 3_000_000L

    /** The steps of "Mobilfunk-Grenze"; null: none. */
    val MOBILE_CAPS: List<Long?> = listOf(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 8_000_000L, null)

    /** The stored value (0: none) as the setting. */
    fun capFromStored(bps: Long): Long? = bps.takeIf { it > 0 }

    /** About how many GB an hour at [bps] costs ("etwa 1,4 GB pro Stunde" for 3 Mbit/s). */
    fun gbPerHour(bps: Long, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", bps * 3600.0 / 8 / 1_000_000_000)

    /** Headphones (wired, Bluetooth, USB, hearing aid): stereo is enough, Jellyfin mixes down on the NAS. */
    private val HEADPHONES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /** The most sound channels to ask for with these outputs connected: 2 with headphones, else 5.1. */
    fun maxChannels(outputs: Set<Int>): Int = if (outputs.any { it in HEADPHONES }) 2 else 6

    /** On mobile data the bit rate is held to [cap] (null: no limit); null when it does not apply. */
    fun mobileLimit(cellular: Boolean, atHome: Boolean?, cap: Long?): Long? = cap?.takeIf { cellular && atHome != true }

    /** What happens with a sound track on this phone (mockup 3): it plays it, or the NAS converts it to AAC. */
    data class AudioNote(val converted: Boolean, val codec: String?, val channels: String?)

    fun audioNote(stream: MediaStream, caps: JellyfinDeviceCaps): AudioNote {
        val fate = MediaTracks.fate(stream, caps)
        if (fate != MediaAudioFate.CONVERTED) return AudioNote(converted = false, codec = MediaTracks.codecLabel(stream.codec), channels = null)
        val to = (stream.channels ?: 2).coerceAtMost(caps.maxAudioChannels)
        return AudioNote(converted = true, codec = MediaTracks.codecLabel(stream.codec), channels = MediaTracks.channelsLabel(if (to > 2) 6 else 2))
    }

    /** "1:48:00" / "42:10" for a place in a video. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    /** The age rating as people read it: "FSK-12" / "12" / "DE-12" → "FSK 12"; others as they are. */
    fun rating(raw: String?): String? {
        val r = raw?.trim()?.ifEmpty { null } ?: return null
        val n = Regex("^(?:FSK|DE)?[- ]?(\\d{1,2})$", RegexOption.IGNORE_CASE).matchEntire(r)?.groupValues?.get(1)
        return if (n != null) "FSK $n" else r
    }
}
