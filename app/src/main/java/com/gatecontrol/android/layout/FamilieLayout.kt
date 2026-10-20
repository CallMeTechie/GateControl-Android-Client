package com.gatecontrol.android.layout

/**
 * The Familie tab's cards in the order and visibility the person chose
 * (pure, unit-tested). Ids are the app's ([DEFAULT_ORDER]); which cards
 * there are depends on the modules and the role (FamilieHomeScreen).
 */
object FamilieLayout {
    const val MEMBERS = "members"
    const val CHILD = "child"
    const val EMERGENCY = "emergency"
    const val TASKS = "tasks"
    const val RIDES = "rides"
    const val SHOPPING = "shopping"
    const val LOCATE = "locate"
    const val DATES = "dates"
    /** Stundenplan of the Schule plugin (not a Familie module). */
    const val SCHOOL = "school"
    const val MEALS = "meals"
    const val BOARD = "board"
    const val ALLOWANCE = "allowance"
    const val WISHES = "wishes"
    const val EXPENSES = "expenses"
    const val PLACES = "places"

    /** The order before anybody arranged the tab. */
    val DEFAULT_ORDER = listOf(MEMBERS, CHILD, EMERGENCY, TASKS, RIDES, SHOPPING, LOCATE, DATES, SCHOOL, MEALS, BOARD, ALLOWANCE, WISHES, EXPENSES, PLACES)

    /**
     * The [available] cards in the [saved] order; a card the order lacks
     * (a module switched on later) joins after the card it follows by
     * default, the first ones at the top. Unknown ids are skipped.
     */
    fun merge(saved: List<String>, available: List<String>): List<String> {
        val availableSet = available.toSet()
        val result = saved.filter { it in availableSet }.distinct().toMutableList()
        val rank = DEFAULT_ORDER.withIndex().associate { (i, id) -> id to i }
        for (card in available.sortedBy { rank[it] ?: Int.MAX_VALUE }) {
            if (card in result) continue
            val own = rank[card] ?: Int.MAX_VALUE
            val after = result.indexOfLast { (rank[it] ?: Int.MAX_VALUE) < own }
            result.add(after + 1, card)
        }
        return result
    }

    /** The cards to show: [order] without the [hidden] ones. */
    fun visible(order: List<String>, hidden: Collection<String>): List<String> {
        val off = hidden.toSet()
        return order.filter { it !in off }
    }

    /**
     * The hidden list to store: the cards hidden now among [available],
     * plus ids hidden before that this device does not show (another
     * role's or module's card stays hidden on the other phone).
     */
    fun hiddenToStore(hiddenNow: Collection<String>, previous: List<String>, available: Collection<String>): List<String> {
        val here = available.toSet()
        return (hiddenNow.filter { it in here } + previous.filter { it !in here }).distinct()
    }
}
