package com.gatecontrol.android.ui.familie

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.layout.FamilieLayout
import com.gatecontrol.android.ui.components.GcIcons

/** The cards of the Familie tab: which there are for a profile (pure, unit-tested), and their names and icons. */
object FamilieCards {

    /**
     * The cards [p] gets, in the default order — the same conditions the
     * start screen shows them under. [schule]: the Schule plugin is listed
     * (PluginAvailability), which adds "Stundenplan" for everybody — the
     * plugin itself answers only with the children the person may see.
     */
    fun available(p: FamilieProfile, schule: Boolean = false): List<String> {
        val m = p.modules
        return buildList {
            add(FamilieLayout.MEMBERS)
            if (p.me.isChild && (m.locate || m.arrivalSos)) add(FamilieLayout.CHILD)
            if (m.emergency) add(FamilieLayout.EMERGENCY)
            if (m.tasks) add(FamilieLayout.TASKS)
            if (m.rides) add(FamilieLayout.RIDES)
            if (m.shopping) add(FamilieLayout.SHOPPING)
            if (p.me.isParent && m.locate && p.children.isNotEmpty()) add(FamilieLayout.LOCATE)
            if (m.dates) add(FamilieLayout.DATES)
            if (schule) add(FamilieLayout.SCHOOL)
            if (m.meals) add(FamilieLayout.MEALS)
            if (m.board) add(FamilieLayout.BOARD)
            if (m.allowance && (p.me.isChild || p.children.isNotEmpty())) add(FamilieLayout.ALLOWANCE)
            if (m.wishes) add(FamilieLayout.WISHES)
            if (m.expenses && p.me.isParent) add(FamilieLayout.EXPENSES)
            if (m.places) add(FamilieLayout.PLACES)
        }
    }

    /** Icon of a card on the arranging screen. */
    fun icon(id: String): ImageVector = when (id) {
        FamilieLayout.MEMBERS -> GcIcons.Family
        FamilieLayout.CHILD -> GcIcons.Phone
        FamilieLayout.EMERGENCY -> GcIcons.Medical
        FamilieLayout.TASKS -> GcIcons.Tasks
        FamilieLayout.RIDES -> GcIcons.Car
        FamilieLayout.SHOPPING -> GcIcons.Cart
        FamilieLayout.LOCATE -> GcIcons.Crosshair
        FamilieLayout.DATES -> GcIcons.Calendar
        FamilieLayout.SCHOOL -> GcIcons.School
        FamilieLayout.MEALS -> GcIcons.Meal
        FamilieLayout.BOARD -> GcIcons.Board
        FamilieLayout.ALLOWANCE -> GcIcons.Wallet
        FamilieLayout.WISHES -> GcIcons.Gift
        FamilieLayout.EXPENSES -> GcIcons.Receipt
        FamilieLayout.PLACES -> GcIcons.Pin
        else -> GcIcons.Family
    }

    /** Name of a card on the arranging screen. */
    @Composable
    fun title(id: String, p: FamilieProfile?): String = stringResource(
        when (id) {
            FamilieLayout.MEMBERS -> R.string.familie_members_title
            FamilieLayout.CHILD -> R.string.familie_child_title
            FamilieLayout.EMERGENCY -> R.string.familie_emergency_title
            FamilieLayout.TASKS -> R.string.familie_today_title
            FamilieLayout.RIDES -> R.string.familie_rides_title
            FamilieLayout.SHOPPING -> R.string.familie_shopping_title
            FamilieLayout.LOCATE -> R.string.familie_locate_title
            FamilieLayout.DATES -> R.string.familie_dates_title
            FamilieLayout.SCHOOL -> R.string.school_title
            FamilieLayout.MEALS -> R.string.familie_meals_title
            FamilieLayout.BOARD -> R.string.familie_board_title
            FamilieLayout.ALLOWANCE -> if (p?.me?.isChild == true) R.string.familie_allowance_mine else R.string.familie_allowance_title
            FamilieLayout.WISHES -> R.string.familie_wishes_title
            FamilieLayout.EXPENSES -> R.string.familie_expenses_title
            FamilieLayout.PLACES -> R.string.familie_places_title
            else -> R.string.familie_title
        },
    )
}
