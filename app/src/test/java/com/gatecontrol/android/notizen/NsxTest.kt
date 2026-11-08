package com.gatecontrol.android.notizen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * The DS Note export read on the phone, against the plugin's fixture `test/fixtures/sample.nsx` of gatecontrol-notizen
 * **1.0.0** (copied unchanged) — the same expectations as the plugin's `test/nsx.test.js`.
 */
class NsxTest {

    private val file = requireNotNull(javaClass.classLoader!!.getResource("notizen/sample.nsx")).readBytes()
    private val arc = Nsx.open(Nsx.Bytes(file))

    @Test
    fun `summary, notes, HTML to Markdown, pictures by ref`() {
        val sum = Nsx.summary(arc)
        assertEquals(listOf(Triple("Haushalt", 2, 0), Triple("Rezepte & Dokumente", 2, 1)), sum.notebooks.map { Triple(it.title, it.notes, it.protectedNotes) })
        assertEquals(4, sum.notes)
        assertEquals(1, sum.protectedNotes)
        assertEquals(2, sum.attachments)
        val n1 = Nsx.note(arc, "N1note1")
        assertEquals("Gartenhaus Maße", n1.title)
        assertEquals(listOf("garten", "bauen"), n1.tags)
        assertEquals(1760000000000L, n1.created)
        assertEquals(listOf(Triple("skizze.png", "image/png", true), Triple("Angebot.pdf", "application/pdf", true)), n1.attachments.map { Triple(it.name, it.mime, it.entry != null) })
        val png = arc.bytes(n1.attachments[0].entry!!)
        assertEquals("PNG", String(png, 1, 3, Charsets.US_ASCII))
        val md = Nsx.toMarkdown(n1.html) { ref -> if (ref == n1.attachments[0].ref) "attnew:0" else null }
        assertEquals("## Maße\n\nBreite **2,40 m**, Tiefe *1,80 m* & Dach 15 °.\n![Bild](attnew:0)", md)
        val n2 = Nsx.note(arc, "N2note2")
        assertEquals("- [x] Zahnbürste\n- [ ] Wanderschuhe\n\n| Tag | Ziel |\n| --- | --- |\n| Sa | Brocken |\n\nLink: [Wetter](https://example.com/harz)", Nsx.toMarkdown(n2.html))
        val n3 = Nsx.note(arc, "N3note3")
        assertEquals("500 g Hack\n\n1. Soße\n2. Schichten", Nsx.toMarkdown(n3.html))
    }

    @Test
    fun `a protected note opens with its password only, not a zip is refused`() {
        val n4 = Nsx.note(arc, "N4note4")
        assertTrue(n4.encrypted)
        assertEquals("<p>Police <b>4711</b></p>", Nsx.decrypt(n4.html, "geheim"))
        assertEquals("decrypt", assertThrows(Nsx.NsxException::class.java) { Nsx.decrypt(n4.html, "falsch") }.code)
        assertEquals("decrypt", assertThrows(Nsx.NsxException::class.java) { Nsx.decrypt("irgendwas", "geheim") }.code)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", MessageDigest.getInstance("MD5").digest("abc".toByteArray()).joinToString("") { "%02x".format(it) })
        assertEquals("invalid", assertThrows(Nsx.NsxException::class.java) { Nsx.open(Nsx.Bytes(ByteArray(1000))) }.code)
    }

    @Test
    fun `refs of the import routes`() {
        assertEquals("NB1aaaa", Nsx.ref("NB1aaaa"))
        assertEquals("abc", Nsx.ref("a/b c"))
        assertEquals("nb", Nsx.ref("///"))
    }
}
