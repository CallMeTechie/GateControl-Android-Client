package com.gatecontrol.android.tv.notizen

import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NNav
import com.gatecontrol.android.notizen.NParsedLine
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NSection
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NView
import com.gatecontrol.android.notizen.NotizenApi
import com.gatecontrol.android.tv.zuhause.TvSourceState
import com.google.gson.JsonObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Notizen on the TV against the plugin's API: only „Privat“, ticking at once and back when it did not go. */
class TvNotizenTest {

    private val api = mockk<NotizenApi>()
    private val notizen = TvNotizen(api)
    private val today = LocalDate.parse("2026-11-24")

    private fun task(id: Long, title: String, done: Boolean = false) = NTask(
        id = id, listId = null, parentId = null, title = title, hasNote = false, important = false, done = done, doneAt = null, due = null,
        overdue = false, remindAt = null, repeat = null, repeatText = null, assignee = null, myDay = true, progress = null, attachments = 0,
        source = null, noteId = null, position = id, updatedAt = 0,
    )

    private val nav = NNav(NArea.PRIVATE, mapOf("myday" to 1, "planned" to 0), emptyList(), emptyList(), emptyList(), 0, emptyList(), emptyList())
    private val myDay = NView("myday", "Mein Tag", listOf(NSection("today", "Heute", 1, listOf(task(2, "Blumen gießen")), false)))

    private fun loaded() = runBlocking {
        coEvery { api.nav(NArea.PRIVATE) } returns NResult.Ok(nav to JsonObject())
        coEvery { api.view("myday", NArea.PRIVATE) } returns NResult.Ok(myDay to JsonObject())
        notizen.refresh(force = true, today = today)
    }

    @Test
    fun `the left column and Mein Tag are asked for the area Privat only`() {
        loaded()
        coVerify(exactly = 1) { api.nav(NArea.PRIVATE) }
        coVerify(exactly = 0) { api.nav(NArea.WORK) }
        assertEquals(TvSourceState.OK, notizen.data.value.navState)
        assertEquals(TvTasksToday(1, 0), TvNotizenLogic.today(notizen.data.value.views["myday"]))
    }

    @Test
    fun `without a connection the tick goes back`() = runBlocking {
        loaded()
        coEvery { api.completeTask(2, true) } returns NResult.Offline
        assertEquals(TvNotesDone.OFFLINE, notizen.tick(task(2, "Blumen gießen"), true))
        assertFalse(TvNotizenLogic.entries(notizen.data.value.views["myday"]).single().done)
    }

    @Test
    fun `a tick that went shows the server's views`() = runBlocking {
        loaded()
        coEvery { api.completeTask(2, true) } returns NResult.Ok(task(2, "Blumen gießen", done = true) to null)
        val after = NView("myday", "Mein Tag", listOf(NSection("today", "Heute", 1, listOf(task(2, "Blumen gießen", done = true)), false)))
        coEvery { api.view("myday", NArea.PRIVATE) } returns NResult.Ok(after to JsonObject())
        assertEquals(TvNotesDone.OK, notizen.tick(task(2, "Blumen gießen"), true))
        assertTrue(TvNotizenLogic.entries(notizen.data.value.views["myday"]).single().done)
    }

    @Test
    fun `a new task goes to the quick entry, in Mein Tag only without another day`() = runBlocking {
        loaded()
        val body = slot<JsonObject>()
        coEvery { api.createTask(capture(body)) } returns NResult.Ok(task(9, "Winterreifen abholen"))
        val parsed = NParsedLine("Winterreifen abholen", NDue("2026-11-25", "17:00"), false)
        assertEquals(TvNotesDone.OK, notizen.add("Winterreifen abholen morgen 17 Uhr", parsed, today, clientId = "c-0001"))
        assertEquals(
            """{"text":"Winterreifen abholen morgen 17 Uhr","area":"private","clientId":"c-0001"}""",
            body.captured.toString(),
        )
        notizen.add("Blumen gießen", null, today, clientId = "c-0002")
        assertEquals(true, body.captured.get("myDay").asBoolean)
    }

    @Test
    fun `unpaired nothing stays`() {
        loaded()
        notizen.clear()
        assertEquals(TvNotizenData(), notizen.data.value)
    }
}
