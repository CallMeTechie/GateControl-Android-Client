package com.gatecontrol.android.notizen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The note text as the app reads and edits it — the plugin's `test/vectors.test.js` "note text" case, and the toolbar. */
class NoteMdTest {

    private val md = "# Werkstatt\n<script>alert(1)</script> **fett** [x](javascript:alert(1)) [ok](https://example.com)\n- [x] eins\n- [ ] zwei [Aufgabe](task:5)\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n![Bild](att:3) #auto #Werkstatt"

    @Test
    fun `blocks - safe, checks with their lines, a table, a picture`() {
        val b = NoteMd.blocks(md)
        assertEquals(NoteMd.Heading(1, "Werkstatt"), b[0])
        val para = b[1] as NoteMd.Paragraph
        val spans = NoteMd.inline(para.lines[0])
        // raw HTML stays text, a javascript: link is no link, https is
        assertTrue(spans.any { it.text.contains("<script>") && it.url == null })
        assertTrue(spans.none { it.url?.startsWith("javascript") == true })
        assertTrue(spans.any { it.text == "fett" && it.bold })
        assertEquals("https://example.com", spans.first { it.text == "ok" }.url)
        assertEquals(NoteMd.Check(2, 0, true, "eins"), b[2])
        assertEquals(NoteMd.Check(3, 0, false, "zwei [Aufgabe](task:5)"), b[3])
        assertEquals(NoteMd.Table(listOf("a", "b"), listOf(listOf("1", "2"))), b[4])
        val pic = NoteMd.inline((b[5] as NoteMd.Paragraph).lines[0])
        assertEquals(NoteMd.Span("Bild", att = 3, image = true), pic[0])
        assertEquals(listOf("auto", "werkstatt"), pic.mapNotNull { it.tag })
        assertEquals(5L, NoteMd.inline("zwei [Aufgabe](task:5)").firstNotNullOf { it.task })
    }

    @Test
    fun `inline marks`() {
        val s = NoteMd.inline("a *kursiv* und ~~weg~~ mit `code **nicht fett**` und https://gatecontrol.de/x.")
        assertTrue(s.any { it.text == "kursiv" && it.italic })
        assertTrue(s.any { it.text == "weg" && it.strike })
        assertTrue(s.any { it.text == "code **nicht fett**" && it.code && !it.bold })
        assertEquals("https://gatecontrol.de/x", s.first { it.url != null }.url)
        assertTrue(s.last().text.endsWith("."))
    }

    @Test
    fun `checks, toggle, tags, attachments, a line to a task`() {
        assertEquals(listOf(NoteMd.CheckLine(2, "eins", true, null), NoteMd.CheckLine(3, "zwei", false, 5)), NoteMd.checks(md))
        assertEquals("- [x] zwei [Aufgabe](task:5)", NoteMd.toggle(md, 3, true)!!.split("\n")[3])
        assertNull(NoteMd.toggle(md, 0, true))
        assertEquals(listOf("auto", "werkstatt"), NoteMd.tags(md))
        assertEquals(listOf(3L), NoteMd.attachments(md))
        assertEquals("zwei", NoteMd.lineText(md, 3))
        assertEquals("- [ ] a [Aufgabe](task:9)", NoteMd.linkTask("- [ ] a", 0, 9, "Aufgabe"))
        // headings are not tags, code is not either
        assertEquals(listOf("ja"), NoteMd.tags("# Titel\n`#nein` text #ja"))
    }

    @Test
    fun `the toolbar`() {
        assertEquals(NoteMd.Edit("ab **cd** ef", 5, 7), NoteMd.wrap("ab cd ef", 3, 5, "**"))
        assertEquals("eins\n- [ ] zwei", NoteMd.linePrefix("eins\nzwei", 7, "- [ ] ").text)
        assertEquals("eins\n## zwei", NoteMd.linePrefix("eins\n- zwei", 7, "## ").text)
        assertEquals("eins\nzwei", NoteMd.linePrefix("eins\n## zwei", 8, "## ").text)
        val t = NoteMd.table("Text", 4, listOf("A", "B")).text
        assertEquals("Text\n\n| A | B |\n| --- | --- |\n|   |   |\n", t)
        assertTrue(NoteMd.blocks(t).any { it is NoteMd.Table })
        assertEquals("a\n![x](att:4)\nb", NoteMd.insertLine("a\nb", 2, "![x](att:4)").text)
        assertEquals(1, NoteMd.lineOf("a\nb", 3))
        assertEquals("Einkauf" to "Milch\nBrot", NoteMd.splitTitle("  Einkauf\nMilch\nBrot "))
        assertFalse(NoteMd.taskable("---", 0))
    }
}
