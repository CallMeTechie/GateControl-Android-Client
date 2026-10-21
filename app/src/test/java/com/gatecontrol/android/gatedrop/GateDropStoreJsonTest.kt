package com.gatecontrol.android.gatedrop

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GateDropStoreJsonTest {

    @Test
    fun `job round trip`() {
        val job = GdJob(
            id = "job_abcdefgh",
            files = listOf(GdJobFile("/cache/gatedrop-out/job/0-a.pdf", "a.pdf", "application/pdf", 12)),
            text = "Hallo",
            link = null,
            note = "Notiz",
            to = GdRecipients(people = listOf("7", "family"), devices = listOf("tok-12"), nas = listOf(GdNasRef("f1", "/Fotos")), link = GdLinkRequest(GdExpiry.HOUR, pin = true)),
            directDevices = listOf("tok-12"),
            createdAt = 1234,
            uploaded = listOf("0123456789abcdef"),
            sendId = "42",
            fallback = true,
            missing = listOf(0, 2),
        )
        val back = GateDropStoreJson.job(JsonParser.parseString(GateDropStoreJson.jobJson(job).toString()).asJsonObject)
        assertEquals(job, back)
    }

    @Test
    fun `received round trip and garbage`() {
        val list = listOf(
            GdReceived("t:tr1", "6", "Laptop", 300, text = "x\ny", link = "https://a.example", note = "n", files = listOf(GdReceivedFile("b.jpg", "image/jpeg", 34, null, "content://dl/2"))),
            GdReceived("s:5", "5", null, 200),
        )
        assertEquals(list, GateDropStoreJson.receivedList(GateDropStoreJson.receivedJson(list)))
        assertTrue(GateDropStoreJson.receivedList("{oops").isEmpty())
        assertTrue(GateDropStoreJson.receivedList(null).isEmpty())
        assertEquals(1, GateDropStoreJson.receivedList("""[{"key":"a","at":1},{"nokey":1}]""").size)
    }
}
