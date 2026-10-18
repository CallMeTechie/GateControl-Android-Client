package com.gatecontrol.android.layout

import com.gatecontrol.android.ui.familie.swapVisible
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FamilieLayoutTest {

    private val available = listOf(FamilieLayout.MEMBERS, FamilieLayout.TASKS, FamilieLayout.SHOPPING, FamilieLayout.DATES, FamilieLayout.BOARD)

    @Test
    fun `without an order the cards keep the default order`() {
        assertEquals(available, FamilieLayout.merge(emptyList(), available.reversed()))
    }

    @Test
    fun `the stored order wins and unknown or unavailable ids are skipped`() {
        val saved = listOf("board", "dates", "nope", "members", "wishes", "shopping", "tasks")
        assertEquals(listOf("board", "dates", "members", "shopping", "tasks"), FamilieLayout.merge(saved, available))
    }

    @Test
    fun `a card switched on later joins after the card it follows by default`() {
        val saved = listOf("dates", "members", "tasks")
        // shopping follows tasks by default; board follows dates → after the last card ranked before it
        val merged = FamilieLayout.merge(saved, available)
        assertEquals(listOf("dates", "members", "tasks", "shopping", "board"), merged)
        // Emergency comes before everything but the members: right after them.
        assertEquals(listOf("tasks", "members", "emergency"), FamilieLayout.merge(listOf("tasks", "members"), listOf("members", "emergency", "tasks")))
        // Nothing ranks before members: it goes to the top.
        assertEquals(listOf("members", "board"), FamilieLayout.merge(listOf("board"), listOf("board", "members")))
    }

    @Test
    fun `hidden cards are left out and stay hidden on other devices`() {
        assertEquals(listOf("members", "dates"), FamilieLayout.visible(listOf("members", "tasks", "dates"), setOf("tasks")))
        assertEquals(
            listOf("tasks", "expenses"),
            FamilieLayout.hiddenToStore(setOf("tasks", "ghost"), previous = listOf("dates", "expenses"), available = available),
        )
    }

    @Test
    fun `move up and down on the arranging screen swaps with the visible neighbour`() {
        val cards = listOf("members", "tasks", "shopping", "dates")
        val visible = listOf("members", "shopping", "dates") // tasks hidden
        assertEquals(listOf("members", "tasks", "dates", "shopping"), swapVisible(cards, visible, 1, 1))
        assertEquals(listOf("shopping", "tasks", "members", "dates"), swapVisible(cards, visible, 1, -1))
        assertEquals(cards, swapVisible(cards, visible, 2, 1))
    }
}
