package com.gatecontrol.android.profile

import com.gatecontrol.android.familie.presence.PresenceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AvatarTest {

    @Test
    fun `palette has the server's ten colours in its order and no red`() {
        assertEquals(
            listOf("blue", "teal", "green", "lime", "yellow", "orange", "pink", "purple", "indigo", "slate"),
            AvatarPalette.DEFAULT.map { it.key },
        )
        assertEquals("#3B82F6", AvatarPalette.hexOf("blue"))
        assertEquals("#64748B", AvatarPalette.hexOf("slate"))
        assertFalse(AvatarPalette.DEFAULT.any { it.key == "red" || it.hex == AvatarPalette.AWAY_HEX })
    }

    @Test
    fun `default colour is palette at id mod 10`() {
        assertEquals("blue", AvatarPalette.defaultFor(10).key)
        assertEquals("teal", AvatarPalette.defaultFor(1).key)
        assertEquals("slate", AvatarPalette.defaultFor(9).key)
        assertEquals("green", AvatarPalette.defaultFor(12).key)
    }

    @Test
    fun `red from a server is never selectable`() {
        val server = listOf(PaletteColor("blue", "#3B82F6"), PaletteColor("red", "#FF0000"), PaletteColor("x", "#e5484d"))
        assertEquals(listOf("blue"), AvatarPalette.selectable(server).map { it.key })
        assertEquals(AvatarPalette.DEFAULT, AvatarPalette.selectable(emptyList()))
    }

    @Test
    fun `hex colours parse to opaque argb and invalid ones are rejected`() {
        assertEquals(0xFF3B82F6L, AvatarPalette.argb("#3b82f6"))
        assertEquals(0xFFE5484DL, AvatarPalette.argb(AvatarPalette.AWAY_HEX))
        assertNull(AvatarPalette.argb("blue"))
        assertNull(AvatarPalette.argb("#12345"))
        assertNull(AvatarPalette.argb(null))
        assertEquals("#ABCDEF", AvatarPalette.validHex(" #abcdef "))
    }

    @Test
    fun `ring follows presence - home favourite colour, away red, otherwise neutral`() {
        assertEquals(AvatarRing.Home("#22C55E"), AvatarRing.of(PresenceState.HOME, "#22c55e"))
        assertEquals(AvatarRing.Home(null), AvatarRing.of(PresenceState.HOME, "green"))
        assertEquals(AvatarRing.Away, AvatarRing.of(PresenceState.AWAY, "#22C55E"))
        assertEquals(AvatarRing.None, AvatarRing.of(PresenceState.UNKNOWN, "#22C55E"))
        assertEquals(AvatarRing.None, AvatarRing.of(PresenceState.NOT_SHARED, "#22C55E"))
        assertEquals(AvatarRing.None, AvatarRing.of(null, "#22C55E"))
    }

    @Test
    fun `initials like the server`() {
        assertEquals("AM", AvatarInitials.of("Anna Müller"))
        assertEquals("AN", AvatarInitials.of("anna"))
        assertEquals("JD", AvatarInitials.of("john.doe"))
        assertEquals("?", AvatarInitials.of("  "))
        assertEquals("?", AvatarInitials.of(null))
        assertEquals("ÉL", AvatarInitials.of("élise"))
        assertEquals("AB", AvatarInitials.pick("AB", "Zoe Young"))
        assertEquals("ZY", AvatarInitials.pick("toolong", "Zoe Young"))
        assertEquals("ZY", AvatarInitials.pick(null, "Zoe Young"))
        assertTrue(AvatarInitials.of("😀x").startsWith("😀"), "surrogate pairs are kept whole")
    }
}
