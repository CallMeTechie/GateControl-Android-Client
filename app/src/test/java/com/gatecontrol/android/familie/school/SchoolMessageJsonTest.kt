package com.gatecontrol.android.familie.school

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The fields of plugin 1.5 (full text, attachments, read state, confirmation) and the answers of its new calls. */
class SchoolMessageJsonTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `the new fields are read`() {
        val m = SchoolJson.message(
            obj(
                """
                {"id":"3-12345","studentIds":[3],"subject":"Elternbrief","preview":"Liebe Eltern","body":"Liebe Eltern,\n\nder Ausflug …",
                 "fullText":true,"hasAttachments":true,"isNew":false,"read":true,
                 "attachments":[
                   {"id":"a1b2","name":"Elternbrief.pdf","size":123456,"mime":"application/pdf"},
                   {"id":"c3","name":"../../etc/Bild.JPG","size":null,"mime":"IMAGE/JPEG; charset=x"},
                   {"id":"d4","name":"","size":-5,"mime":"not a type"},
                   {"id":"bad/id","name":"x"},
                   {"name":"no id"},
                   {"id":"a1b2","name":"duplicate"}
                 ],
                 "confirmation":{"confirmedAt":"2026-10-19T07:15:00Z","by":"Anna <b>M.</b>"}}
                """.trimIndent(),
            ),
        )!!
        assertTrue(m.fullText)
        assertTrue(m.extended)
        assertTrue(m.read)
        assertFalse(m.isNew)
        assertEquals("Liebe Eltern,\n\nder Ausflug …", m.text)
        assertEquals(listOf("a1b2", "c3", "d4"), m.attachments.map { it.id }, "path-unsafe ids, missing ids and duplicates dropped")
        assertEquals(SchoolAttachment("a1b2", "Elternbrief.pdf", 123456L, "application/pdf"), m.attachments[0])
        assertEquals(SchoolAttachment("c3", "Bild.JPG", null, "image/jpeg"), m.attachments[1], "no path in the name, the type in lower case without parameters")
        assertEquals(SchoolAttachment("d4", "", null, null), m.attachments[2])
        assertEquals(SchoolConfirmation(confirmed = true, confirmedAt = 1792394100000L, by = "Anna M."), m.confirmation)
        assertFalse(m.confirmationPending)
    }

    @Test
    fun `a confirmation asked for and not sent yet`() {
        val m = SchoolJson.message(obj("""{"id":"1","read":false,"isNew":true,"confirmation":{"confirmedAt":null,"by":"ignored"}}"""))!!
        assertEquals(SchoolConfirmation(confirmed = false), m.confirmation)
        assertTrue(m.confirmationPending)
        assertTrue(m.isNew)

        val noTime = SchoolJson.message(obj("""{"id":"1","confirmation":{"confirmedAt":"yesterday"}}"""))!!
        assertTrue(noTime.confirmation!!.confirmed, "set but not a valid time: still sent")
        assertNull(noTime.confirmation!!.confirmedAt)

        assertNull(SchoolJson.message(obj("""{"id":"1","confirmation":null}"""))!!.confirmation)
    }

    @Test
    fun `an older plugin's message reads as before`() {
        val m = SchoolJson.message(obj("""{"id":"1","subject":"S","preview":"P","body":"Text","hasAttachments":true,"isNew":true}"""))!!
        assertFalse(m.fullText)
        assertFalse(m.extended)
        assertFalse(m.read)
        assertTrue(m.isNew)
        assertTrue(m.hasAttachments)
        assertTrue(m.attachments.isEmpty())
        assertNull(m.confirmation)
        assertFalse(m.confirmationPending)
    }

    @Test
    fun `read wins over isNew, fullText needs a body, attachments imply the clip`() {
        assertFalse(SchoolJson.message(obj("""{"id":"1","isNew":true,"read":true}"""))!!.isNew)
        val noBody = SchoolJson.message(obj("""{"id":"1","fullText":true,"preview":"P"}"""))!!
        assertFalse(noBody.fullText)
        assertTrue(noBody.extended)
        assertEquals("P", noBody.text)
        assertTrue(SchoolJson.message(obj("""{"id":"1","attachments":[{"id":"a"}]}"""))!!.hasAttachments)
    }

    @Test
    fun `the answers of read, confirm and attachments`() {
        val a = SchoolJson.messageAnswer(obj("""{"ok":true,"message":{"id":"1","isNew":false,"read":true}}"""))
        assertEquals("1", a.message?.id)
        assertNotNull(a.raw)
        val empty = SchoolJson.messageAnswer(obj("""{"ok":true}"""))
        assertNull(empty.message)
        assertNull(empty.raw)
        assertNull(SchoolJson.messageAnswer(obj("""{"ok":true,"message":{"subject":"no id"}}""")).raw)

        assertEquals(
            SchoolAttachmentFile("0123456789abcdef0123456789abcdef", "Brief.pdf", "application/pdf", 1234L),
            SchoolJson.attachmentFile(obj("""{"ok":true,"file":{"mediaId":"0123456789abcdef0123456789abcdef","name":"Brief.pdf","mime":"application/pdf","bytes":1234}}""")),
        )
        assertNull(SchoolJson.attachmentFile(obj("""{"ok":true,"file":{"mediaId":"../x"}}""")))
        assertNull(SchoolJson.attachmentFile(obj("""{"ok":true}""")))
    }

    @Test
    fun `a message from read or confirm replaces its entry in the stored answer`() {
        val raw = """{"ok":true,"messages":[{"id":"1","isNew":true},{"id":"2","isNew":true}]}"""
        val replaced = SchoolJson.replaceMessage(raw, obj("""{"id":"2","isNew":false,"read":true}"""))
        val list = SchoolJson.messages(replaced)!!
        assertTrue(list.first { it.id == "1" }.isNew)
        assertFalse(list.first { it.id == "2" }.isNew)
        assertTrue(list.first { it.id == "2" }.read)

        assertEquals(raw, SchoolJson.replaceMessage(raw, obj("""{"id":"3"}""")), "not in the list: unchanged")
        assertNull(SchoolJson.replaceMessage("not json", obj("""{"id":"1"}""")))
        assertNull(SchoolJson.replaceMessage(null, obj("""{"id":"1"}""")))
    }
}
