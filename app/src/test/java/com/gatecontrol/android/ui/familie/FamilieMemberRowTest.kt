package com.gatecontrol.android.ui.familie

import com.gatecontrol.android.familie.FamilieMe
import com.gatecontrol.android.familie.FamilieMember
import com.gatecontrol.android.familie.FamilieModules
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRole
import com.gatecontrol.android.familie.MemberFace
import com.gatecontrol.android.familie.presence.PresenceState
import com.gatecontrol.android.profile.AvatarRing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FamilieMemberRowTest {

    private fun member(id: Int, name: String, role: FamilieRole = FamilieRole.PARENT, face: MemberFace? = null) =
        FamilieMember(id, name, role, null, "#22C55E", null, null, face = face)

    private fun profile(meId: Int, members: List<FamilieMember>) = FamilieProfile(
        me = FamilieMe(meId, members.first { it.userId == meId }.name, FamilieRole.CHILD, 9, null),
        modules = FamilieModules(presence = true),
        members = members,
        serverTime = null,
    )

    @Test
    fun `own avatar always first, everybody once`() {
        val anna = member(1, "Anna")
        val tim = member(2, "Tim", FamilieRole.CHILD)
        val lena = member(3, "Lena", FamilieRole.CHILD)
        // The server lists me somewhere in the middle (and twice).
        val p = profile(meId = 2, members = listOf(anna, tim, lena, tim))
        assertEquals(listOf(2, 1, 3), FamilieMemberRow.ordered(p).map { it.userId })
        // Not listed among the members at all: still first.
        val p2 = profile(meId = 2, members = listOf(tim)).copy(members = listOf(anna, lena))
        assertEquals(listOf(2, 1, 3), FamilieMemberRow.ordered(p2).map { it.userId })
    }

    @Test
    fun `own ring from what this phone senses, others from the server`() {
        val face = MemberFace(colorHex = "#3B82F6", initials = "AN", presence = "away")
        val anna = member(1, "Anna", face = face)
        val me = member(2, "Tim", face = MemberFace(colorHex = "#F97316", presence = "away"))
        val own = AvatarRing.Home("#F97316")
        // Me: the start tab's ring, even though the server still says "away".
        assertEquals(own, FamilieMemberRow.ringOf(me, meId = 2, ownRing = own, serverState = PresenceState.AWAY))
        assertEquals(AvatarRing.None, FamilieMemberRow.ringOf(me, meId = 2, ownRing = AvatarRing.None, serverState = PresenceState.HOME))
        // Others: the fresher server state, else the member's face.
        assertEquals(AvatarRing.Home("#3B82F6"), FamilieMemberRow.ringOf(anna, meId = 2, ownRing = own, serverState = PresenceState.HOME))
        assertEquals(AvatarRing.Away, FamilieMemberRow.ringOf(anna, meId = 2, ownRing = own, serverState = null))
        assertEquals(AvatarRing.None, FamilieMemberRow.ringOf(anna, meId = 2, ownRing = own, serverState = PresenceState.NOT_SHARED))
        // Older plugin without faces: no ring, the old look.
        assertNull(FamilieMemberRow.ringOf(member(3, "Lena"), meId = 2, ownRing = own, serverState = PresenceState.HOME))
    }
}
