package com.gatecontrol.android.media

import android.media.AudioDeviceInfo
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/** The phone's media rules: the mobile data limit (question 28), stereo with headphones, the notes of the sound. */
class MediaPhoneLogicTest {

    @Test
    fun `3 Mbit per second on mobile data by default, about 1,4 GB an hour`() {
        assertEquals(3_000_000L, MediaPhoneLogic.DEFAULT_MOBILE_CAP_BPS)
        assertEquals("1,4", MediaPhoneLogic.gbPerHour(3_000_000L, Locale.GERMANY))
        assertEquals("1.4", MediaPhoneLogic.gbPerHour(3_000_000L, Locale.US))
        assertNull(MediaPhoneLogic.capFromStored(0))
        assertEquals(2_000_000L, MediaPhoneLogic.capFromStored(2_000_000))
        assertTrue(null in MediaPhoneLogic.MOBILE_CAPS)
        // the limit holds on mobile data away only
        assertEquals(3_000_000L, MediaPhoneLogic.mobileLimit(cellular = true, atHome = false, cap = 3_000_000L))
        assertNull(MediaPhoneLogic.mobileLimit(cellular = false, atHome = false, cap = 3_000_000L))
        assertNull(MediaPhoneLogic.mobileLimit(cellular = true, atHome = true, cap = 3_000_000L))
        assertNull(MediaPhoneLogic.mobileLimit(cellular = true, atHome = false, cap = null))
    }

    @Test
    fun `stereo is enough with headphones`() {
        assertEquals(2, MediaPhoneLogic.maxChannels(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertEquals(2, MediaPhoneLogic.maxChannels(setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        assertEquals(6, MediaPhoneLogic.maxChannels(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertEquals(6, MediaPhoneLogic.maxChannels(emptySet()))
    }

    @Test
    fun `DTS is converted to AAC 5_1, with headphones to stereo, AAC plays as it is`() {
        val phone = MediaDeviceCaps.fromDecoders(listOf("video/avc", "audio/mp4a-latm"), hdrScreen = false, name = "GateControl")
        val dts = MediaStream(1, MediaStream.Type.AUDIO, codec = "dts", channels = 6)
        val note = MediaPhoneLogic.audioNote(dts, phone)
        assertTrue(note.converted)
        assertEquals("DTS", note.codec)
        assertEquals("5.1", note.channels)
        val headphones = MediaDeviceCaps.fromDecoders(emptyList(), hdrScreen = false, name = "GateControl", maxAudioChannels = 2)
        assertEquals("Stereo", MediaPhoneLogic.audioNote(dts, headphones).channels)
        assertFalse(MediaPhoneLogic.audioNote(MediaStream(2, MediaStream.Type.AUDIO, codec = "aac", channels = 2), phone).converted)
        // nothing goes on to a receiver from a phone
        assertTrue(phone.passthrough.isEmpty())
        assertFalse(MediaPhoneLogic.audioNote(dts, JellyfinDeviceCaps(passthrough = setOf("dts"))).converted)
    }

    @Test
    fun `the clock and the age rating`() {
        assertEquals("42:10", MediaPhoneLogic.clock(42 * 60_000L + 10_000))
        assertEquals("1:48:00", MediaPhoneLogic.clock(108 * 60_000L))
        assertEquals("FSK 12", MediaPhoneLogic.rating("FSK-12"))
        assertEquals("FSK 16", MediaPhoneLogic.rating("DE-16"))
        assertEquals("FSK 0", MediaPhoneLogic.rating("0"))
        assertEquals("PG-13", MediaPhoneLogic.rating("PG-13"))
        assertNull(MediaPhoneLogic.rating(" "))
    }
}
