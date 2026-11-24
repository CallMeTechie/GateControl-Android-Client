package com.gatecontrol.android.tv.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvComposerLogicTest {

    private fun marked(text: String) = TvComposerLogic.marks(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun smoothingMakesTheFirstWordCapitalAndEndsWithAFullStop() {
        assertEquals("Ich hole die Pizza, bin um halb sieben da.", TvComposerLogic.smooth("  ich hole die Pizza , bin um halb  sieben da "))
        assertEquals("Kommst du mit?", TvComposerLogic.smooth("kommst du mit?"))
        assertEquals("Super!", TvComposerLogic.smooth("super!"))
        assertEquals("Danke.", TvComposerLogic.smooth("danke,"))
        assertEquals("Liste:", TvComposerLogic.smooth("liste:"))
        assertEquals("„Toll“", TvComposerLogic.smooth("„toll“"))
        assertEquals("Erste Zeile\nzweite Zeile.", TvComposerLogic.smooth("erste Zeile\n zweite   Zeile"))
        assertEquals("Bis dann 👍", TvComposerLogic.smooth("bis dann 👍"))
        assertEquals("", TvComposerLogic.smooth("   "))
    }

    @Test
    fun numbersAndTimesAreMarked() {
        assertEquals(listOf("halb sieben"), marked("Ich hole die Pizza, bin um halb sieben da."))
        assertEquals(listOf("17:30"), marked("Treffen um 17:30 am Tor"))
        assertEquals(listOf("3", "10"), marked("Am 3. Dezember nicht, aber am 10. Dezember"))
        assertEquals(listOf("17 Uhr"), marked("morgen 17 Uhr"))
        assertEquals(listOf("3.12"), marked("bin am 3.12. zu Hause"))
        assertEquals(listOf("seven thirty"), marked("see you at seven thirty"))
        assertTrue("„Uhr“ alone is no time", marked("um wie viel Uhr?").isEmpty())
        assertTrue(marked("Passt, bis dann!").isEmpty())
    }

    @Test
    fun buildingBlocksGoIntoTheDraft() {
        assertEquals("Passt, bis dann!", TvComposerLogic.insert("", " Passt, bis dann! "))
        assertEquals("Danke dir. Passt, bis dann!", TvComposerLogic.insert("Danke dir. ", "Passt, bis dann!"))
        assertEquals("Ja ", TvComposerLogic.continued("Ja"))
        assertEquals("Ja ", TvComposerLogic.continued("Ja "))
        assertEquals("", TvComposerLogic.continued(""))
    }

    @Test
    fun ownBuildingBlocksAreKeptClean() {
        val many = (1..40).map { "Baustein $it" }
        assertEquals(TvComposerLogic.MAX_SNIPPETS, TvComposerLogic.cleanSnippets(many).size)
        assertEquals(listOf("Danke", "Bis dann"), TvComposerLogic.cleanSnippets(listOf(" Danke ", "", "Danke", "Bis dann")))
        assertEquals(TvComposerLogic.MAX_SNIPPET, TvComposerLogic.cleanSnippets(listOf("x".repeat(500))).single().length)
    }

    @Test
    fun buildingBlocksMoveUpAndDown() {
        val list = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), TvComposerLogic.move(list, 1, -1))
        assertEquals(listOf("a", "c", "b"), TvComposerLogic.move(list, 1, 1))
        assertEquals(list, TvComposerLogic.move(list, 0, -1))
        assertEquals(list, TvComposerLogic.move(list, 2, 1))
        assertEquals(list, TvComposerLogic.move(list, 5, 1))
    }
}
