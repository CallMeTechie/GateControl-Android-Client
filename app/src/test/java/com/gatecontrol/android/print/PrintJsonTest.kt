package com.gatecontrol.android.print

import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.network.ClientPlugin
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The answers of the print plugin's person API (plugin README "API", server/api.js `overview`, jobs.js `view`). */
class PrintJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val overview = json(
        """
        {"ok":true,
         "rights":{"print":true,"scan":true,"pagesPerDay":20,"bwOnly":true},
         "pagesLeft":12,
         "printers":[
           {"id":"u:6f1c-1","name":"HP OfficeJet Pro 8720","location":"Arbeitszimmer","online":true,"problem":null,
            "printer":{"state":"idle","color":true,"duplex":true,"copiesMax":99},"scanner":null},
           {"id":"h:abc","name":"Brother\u0007 HL","location":null,"online":false,"problem":"cert_changed",
            "printer":{"state":"weird","color":false,"duplex":false,"copiesMax":250},"scanner":null},
           {"id":"../etc","name":"bad id","printer":{"state":"idle"}},
           {"id":"u:scanner-only","name":"Scanner","printer":null},
           {"id":"u:6f1c-1","name":"duplicate","printer":{"state":"idle"}}
         ],
         "scanners":[],
         "jobs":[
           {"kind":"print","id":"pj_AbC-123","user":3,"device":"HP OfficeJet Pro 8720","deviceId":"u:6f1c-1","name":"Bordkarte.pdf",
            "pages":2,"copies":1,"color":false,"duplex":false,"via":"app","state":"waiting","error":"printer_offline","message":null,"open":true},
           {"kind":"print","id":"pj_done","name":"x","pages":1,"copies":2,"state":"done","error":null,"message":"unconfirmed"},
           {"kind":"scan","id":"sc_1","state":"done"},
           {"kind":"print","id":"bad id!","state":"done"},
           {"kind":"print","id":"pj_nostate"}
         ]}
        """.trimIndent(),
    )

    @Test
    fun `overview keeps valid printers and print jobs only`() {
        val o = PrintJson.overview(overview)!!
        assertEquals(PRights(print = true, pagesPerDay = 20, bwOnly = true), o.rights)
        assertEquals(12, o.pagesLeft)
        assertEquals(listOf("u:6f1c-1", "h:abc"), o.printers.map { it.id })
        val hp = o.printers[0]
        assertEquals("HP OfficeJet Pro 8720", hp.name)
        assertEquals("Arbeitszimmer", hp.location)
        assertTrue(hp.online && hp.color && hp.duplex)
        assertEquals("idle", hp.state)
        assertEquals(99, hp.copiesMax)
        val brother = o.printers[1]
        assertEquals("Brother HL", brother.name)
        assertEquals("cert_changed", brother.problem)
        assertEquals("idle", brother.state)
        assertEquals(99, brother.copiesMax)
        assertFalse(brother.online)
        assertEquals(listOf("pj_AbC-123", "pj_done"), o.jobs.map { it.id })
        assertEquals("waiting", o.jobs[0].state)
        assertEquals("printer_offline", o.jobs[0].error)
        assertEquals("unconfirmed", o.jobs[1].message)
        assertEquals(2, o.jobs[1].copies)
    }

    @Test
    fun `no print right means no printers, whatever the answer says`() {
        val o = PrintJson.overview(json("""{"ok":true,"rights":{"print":false,"scan":true},"pagesLeft":5,"printers":[{"id":"u:1","name":"P","printer":{"state":"idle"}}],"jobs":[]}"""))!!
        assertFalse(o.rights.print)
        assertTrue(o.printers.isEmpty())
        assertNull(o.pagesLeft)
    }

    @Test
    fun `an answer without rights is invalid`() {
        assertNull(PrintJson.overview(json("""{"ok":true,"printers":[]}""")))
    }

    @Test
    fun `print answer and media id`() {
        val p = PrintJson.printed(json("""{"ok":true,"job":{"id":"pj_x1","name":"Foto.jpg","state":"queued","pages":1,"copies":3},"pagesLeft":null}"""))!!
        assertEquals("pj_x1", p.job.id)
        assertEquals("queued", p.job.state)
        assertNull(p.pagesLeft)
        assertNull(PrintJson.printed(json("""{"ok":true}""")))
        assertEquals("m_1-2.3~", PrintJson.mediaId(json("""{"ok":true,"mediaId":"m_1-2.3~","mime":"application/pdf","bytes":10}""")))
        assertNull(PrintJson.mediaId(json("""{"ok":true,"mediaId":"a/b"}""")))
        assertEquals(true, PrintJson.canceled(json("""{"ok":true,"canceled":true}""")))
    }

    @Test
    fun `errors of the plugin and the core`() {
        val limit = json("""{"ok":false,"code":"limit","error":"Das wären 6 Seiten – heute gehen noch 4.","left":4,"pages":6}""")
        assertTrue(PrintJson.isRefusal(limit))
        assertEquals("limit", PrintJson.errorCode(limit))
        assertEquals("Das wären 6 Seiten – heute gehen noch 4.", PrintJson.errorText(limit))
        assertEquals(4, PrintJson.errorLeft(limit))
        assertEquals(6, PrintJson.errorPages(limit))
        // the core's own answers (bridge 404, media 415)
        assertEquals("not_found", PrintJson.errorCode(json("""{"ok":false,"code":"not_found"}""")))
        assertNull(PrintJson.errorText(json("""{"ok":false,"code":"not_found"}""")))
        assertEquals("unsupported_type", PrintJson.errorCode(json("""{"ok":false,"error":{"code":"unsupported_type"}}""")))
        assertNull(PrintJson.errorCode(json("""{"ok":false,"code":"Not A Code"}""")))
    }

    @Test
    fun `ids are checked before they go into a path`() {
        assertTrue(PrintJson.validJobId("pj_AbC-123"))
        assertFalse(PrintJson.validJobId("../x"))
        assertFalse(PrintJson.validJobId(null))
        assertTrue(PrintJson.validDeviceId("u:2b6e-77"))
        assertTrue(PrintJson.validDeviceId("h:0123456789abcdef"))
        assertFalse(PrintJson.validDeviceId("u:a/b"))
    }

    @Test
    fun `the plugin list switches printing on`() {
        assertTrue(PluginAvailability.from(listOf(ClientPlugin(id = PRINT_PLUGIN))).print)
        assertFalse(PluginAvailability.from(listOf(ClientPlugin(id = "gatecontrol-drive"))).print)
        assertNotNull(PluginAvailability.NONE)
        assertFalse(PluginAvailability.NONE.print)
    }

    @Test
    fun `plugin 1_1 - office, paper sizes and GET jobs id`() {
        val o = json(
            """
            {"ok":true,"rights":{"print":true},"printers":[
              {"id":"u:1","name":"HP","online":true,"printer":{"state":"idle","office":true,
               "media":{"sizes":["iso_a4_210x297mm","na_index-4x6_4x6in","../x","A4"],"default":"na_index-4x6_4x6in","sources":["main"]}}},
              {"id":"u:2","name":"Brother","online":true,"printer":{"state":"idle","media":{"sizes":"nope","default":"iso_a4_210x297mm"}}}],
             "jobs":[]}
            """,
        )
        val ov = PrintJson.overview(o)!!
        val hp = ov.printers[0]
        assertTrue(hp.office)
        assertEquals(listOf("iso_a4_210x297mm", "na_index-4x6_4x6in"), hp.mediaSizes)
        assertEquals("na_index-4x6_4x6in", hp.mediaDefault)
        val bro = ov.printers[1]
        assertFalse(bro.office)
        assertEquals(emptyList<String>(), bro.mediaSizes)
        assertNull(bro.mediaDefault, "a default not among the sizes is dropped")
        val j = PrintJson.jobAnswer(json("""{"ok":true,"job":{"id":"pj_9","name":"Brief.docx","state":"printing","message":"media-empty","pages":4,"copies":1,"media":"iso_a5_148x210mm"}}"""))!!
        assertEquals(listOf("printing", "media-empty", "iso_a5_148x210mm"), listOf(j.state, j.message, j.media))
        assertEquals(4, j.pages)
        assertNull(PrintJson.jobAnswer(json("""{"ok":true,"job":{"id":"../x","state":"done"}}""")))
        assertNull(PrintJson.job(json("""{"id":"pj_1","state":"done","media":"bad name"}"""))!!.media)
        assertTrue(PrintJson.validMedia("na_letter_8.5x11in"))
        assertFalse(PrintJson.validMedia("A4"))
    }
}
