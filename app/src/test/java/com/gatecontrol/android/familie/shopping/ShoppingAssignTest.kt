package com.gatecontrol.android.familie.shopping

import com.gatecontrol.android.familie.FamilieJson
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Familie B3: who was asked to do the shopping, in the lists of `GET shopping`. */
class ShoppingAssignTest {

    private fun lists(s: String) = FamilieJson.shopping(JsonParser.parseString(s).asJsonObject).lists

    @Test
    fun `assignee as an object, as a number or not at all`() {
        val l = lists(
            """{"lists":[
                {"id":"list-aaaaaaaa","name":"Wocheneinkauf","assignee":{"userId":5,"name":"Tim"},"assignedBy":3,"dueAt":"2026-10-20T10:00:00Z"},
                {"id":"list-bbbbbbbb","name":"Getränke","assignee":7},
                {"id":"list-cccccccc","name":"Baumarkt","assigneeId":"8"},
                {"id":"list-dddddddd","name":"Drogerie"}]}""",
        )
        assertEquals(5, l[0].assigneeId)
        assertEquals("Tim", l[0].assigneeName)
        assertEquals(3, l[0].assignedBy)
        assertEquals("2026-10-20T10:00:00Z", l[0].dueAt)
        assertEquals(7, l[1].assigneeId)
        assertEquals(8, l[2].assigneeId)
        assertNull(l[3].assigneeId)
        assertNull(l[3].dueAt)
    }
}
