package com.gatecontrol.android.ui.profile

import com.gatecontrol.android.familie.presence.PresenceSense
import com.gatecontrol.android.familie.presence.PresenceState
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.profile.MyProfile
import com.gatecontrol.android.profile.ProfileState
import com.gatecontrol.android.profile.ProfileSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MyAvatarUiTest {

    private val anna = MyProfile(7, "Anna", "AN", "green", "#22C55E", "/avatars/AbCdEfGhIjKlMnOpQrStUv.webp", emptyList())

    @Test
    fun `profile from the server - avatar, colour and own presence ring`() {
        val ui = MyAvatarUi.from(ProfileState(anna, ProfileSupport.SUPPORTED), "Anna F", PresenceState.HOME)
        assertTrue(ui.profileAvailable)
        assertEquals("AN", ui.initials)
        assertEquals(anna.avatarUrl, ui.avatarUrl)
        assertEquals(AvatarRing.Home("#22C55E"), ui.ring)
        assertEquals(AvatarRing.Away, MyAvatarUi.from(ProfileState(anna, ProfileSupport.SUPPORTED), null, PresenceState.AWAY).ring)
        assertEquals(AvatarRing.None, MyAvatarUi.from(ProfileState(anna, ProfileSupport.SUPPORTED), null, null).ring)
    }

    @Test
    fun `older server - initials of the Familie name, else the old header`() {
        val old = MyAvatarUi.from(ProfileState(null, ProfileSupport.UNSUPPORTED), "Marc Weber", null)
        assertFalse(old.profileAvailable)
        assertEquals("MW", old.initials)
        assertNull(old.avatarUrl)
        val nothing = MyAvatarUi.from(ProfileState(null, ProfileSupport.UNSUPPORTED), null, null)
        assertNull(nothing.initials)
    }

    @Test
    fun `own presence only while shared and the module is on`() {
        assertNull(MyAvatarUi.ownPresence(share = false, moduleOn = true, sense = PresenceSense.HOME, lastReported = true))
        assertNull(MyAvatarUi.ownPresence(share = true, moduleOn = false, sense = PresenceSense.HOME, lastReported = true))
        assertEquals(PresenceState.HOME, MyAvatarUi.ownPresence(true, true, PresenceSense.HOME, null))
        assertEquals(PresenceState.AWAY, MyAvatarUi.ownPresence(true, true, PresenceSense.AWAY, true))
        assertEquals(PresenceState.HOME, MyAvatarUi.ownPresence(true, true, PresenceSense.UNKNOWN, true))
        assertNull(MyAvatarUi.ownPresence(true, true, PresenceSense.UNKNOWN, null))
    }
}
