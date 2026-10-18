package com.gatecontrol.android.ui.familie

import com.gatecontrol.android.familie.FamilieMe
import com.gatecontrol.android.familie.FamilieMember
import com.gatecontrol.android.familie.FamilieModules
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRole
import com.gatecontrol.android.layout.FamilieLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FamilieCardsTest {

    private val all = FamilieModules(
        shopping = true, tasks = true, dates = true, locate = true, arrivalSos = true, places = true, rides = true,
        emergency = true, allowance = true, wishes = true, expenses = true, board = true, meals = true,
    )
    private val child = FamilieMember(2, "Tim", FamilieRole.CHILD, 9, null, null, null)

    private fun profile(role: FamilieRole, modules: FamilieModules = all, members: List<FamilieMember> = listOf(child)) = FamilieProfile(
        me = FamilieMe(if (role == FamilieRole.CHILD) 2 else 1, "Me", role, null, null),
        modules = modules,
        members = members,
        serverTime = null,
    )

    @Test
    fun `parents get every card but the child's, in the default order`() {
        val cards = FamilieCards.available(profile(FamilieRole.PARENT))
        assertEquals(FamilieLayout.DEFAULT_ORDER - FamilieLayout.CHILD, cards)
    }

    @Test
    fun `children get their own card, no locating and no household book`() {
        val cards = FamilieCards.available(profile(FamilieRole.CHILD))
        assertEquals(FamilieLayout.DEFAULT_ORDER - FamilieLayout.LOCATE - FamilieLayout.EXPENSES, cards)
    }

    @Test
    fun `modules switched off and missing children leave their cards out`() {
        val cards = FamilieCards.available(profile(FamilieRole.PARENT, FamilieModules(tasks = true, locate = true, allowance = true), members = emptyList()))
        assertEquals(listOf(FamilieLayout.MEMBERS, FamilieLayout.TASKS), cards)
    }
}
