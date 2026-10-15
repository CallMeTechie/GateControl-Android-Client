package com.gatecontrol.android.ui.familie

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.familie.FamilieMember
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.presence.PresenceState
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.ui.components.GcAvatar
import com.gatecontrol.android.ui.profile.MyAvatarUi

/**
 * The members of the Familie tab, pure (unit-tested): the own avatar
 * first, then the others; the own ring comes from what this phone senses
 * (the start tab's [MyAvatarUi.ring]) so both tabs always agree, the
 * others' from the server.
 */
object FamilieMemberRow {

    /** Me first, then everybody else, each once. */
    fun ordered(p: FamilieProfile): List<FamilieMember> {
        val all = p.everyone
        val me = all.firstOrNull { it.userId == p.me.userId }
        return listOfNotNull(me) + all.filter { it.userId != p.me.userId }.distinctBy { it.userId }
    }

    /**
     * Ring of [m]: me → [ownRing] (the start tab's source); others → the
     * server's state ([serverState] from a fresher overview / `GET presence`,
     * else the member's face). Null: an older plugin without faces, the
     * avatar keeps its old look.
     */
    fun ringOf(m: FamilieMember, meId: Int, ownRing: AvatarRing, serverState: PresenceState?): AvatarRing? {
        if (m.userId == meId) return ownRing
        val face = m.face ?: return null
        return AvatarRing.of(serverState ?: PresenceState.fromWire(face.presence), face.colorHex ?: m.color)
    }
}

/**
 * A member's avatar in the Familie tab. My own one shows the same picture,
 * colour and ring as the start tab header ([me]); the others as the server
 * has them.
 */
@Composable
fun FamilieMemberAvatar(
    m: FamilieMember,
    p: FamilieProfile,
    me: MyAvatarUi,
    serverState: PresenceState?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = null,
) {
    val isMe = m.userId == p.me.userId
    if (isMe && me.initials != null) {
        GcAvatar(
            url = me.avatarUrl ?: m.face?.avatarUrl,
            initials = me.initials,
            colorHex = me.colorHex ?: m.face?.colorHex ?: m.color,
            ring = me.ring,
            modifier = modifier,
            size = size,
            contentDescription = contentDescription,
        )
        return
    }
    FamilieAvatar(
        m.name, m.color,
        modifier = modifier,
        size = size,
        highlighted = isMe,
        face = m.face,
        presence = serverState,
        ring = FamilieMemberRow.ringOf(m, p.me.userId, me.ring, serverState),
    )
}
