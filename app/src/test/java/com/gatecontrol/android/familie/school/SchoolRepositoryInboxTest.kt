package com.gatecontrol.android.familie.school

import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Messages and absences in the repository: an older plugin (404) hides them, trouble keeps the last lists. */
class SchoolRepositoryInboxTest {

    private val api = mockk<FamilieApi>()
    private val storage = mockk<FamilieStorage> {
        every { ownerKey() } returns "owner"
        every { read<Any?>(any(), any()) } returns null
        every { write(any(), any()) } just runs
        every { delete(any()) } just runs
    }
    private val repository = SchoolRepository(api, storage)

    private val message = SchoolMessage(id = "1", studentIds = listOf(3), isNew = true)
    private val absence = SchoolAbsence(id = "a", studentId = 3, date = "2026-10-20")

    private fun messagesOk() = FamilieResult.Ok(SchoolListFetch(listOf(message), """{"messages":[{"id":"1","studentIds":[3],"isNew":true}]}"""))
    private fun absencesOk() = FamilieResult.Ok(SchoolListFetch(listOf(absence), """{"absences":[{"id":"a","studentId":3,"date":"2026-10-20"}]}"""))

    @Test
    fun `both lists load and are kept on the device`() = runBlocking {
        coEvery { api.schoolMessages(30) } returns messagesOk()
        coEvery { api.schoolAbsences(60) } returns absencesOk()
        val inbox = repository.refreshInbox()
        assertEquals(listOf(message), inbox.messages)
        assertEquals(listOf(absence), inbox.absences)
        assertNull(inbox.messagesError)
        assertTrue(inbox.hasMessages && inbox.hasAbsences)
        assertEquals(inbox, repository.inbox.value)
        verify { storage.write("school-inbox.json", any()) }
    }

    @Test
    fun `an older plugin answers 404 - the views are not offered, without an error`() = runBlocking {
        coEvery { api.schoolMessages(any()) } returns FamilieResult.Failed("not_found", 404)
        coEvery { api.schoolAbsences(any()) } returns FamilieResult.Failed("http_404", 404)
        val inbox = repository.refreshInbox()
        assertNull(inbox.messages)
        assertNull(inbox.absences)
        assertNull(inbox.messagesError)
        assertNull(inbox.absencesError)
        assertFalse(inbox.hasMessages)
        assertFalse(inbox.hasAbsences)
    }

    @Test
    fun `trouble keeps the last lists and tells it, 404 later drops them`() = runBlocking {
        coEvery { api.schoolMessages(any()) } returns messagesOk()
        coEvery { api.schoolAbsences(any()) } returns absencesOk()
        repository.refreshInbox()

        coEvery { api.schoolMessages(any()) } returns FamilieResult.Offline
        coEvery { api.schoolAbsences(any()) } returns FamilieResult.Failed("http_500", 500)
        val stale = repository.refreshInbox()
        assertEquals(listOf(message), stale.messages)
        assertEquals(FamilieApi.CODE_OFFLINE, stale.messagesError)
        assertEquals(listOf(absence), stale.absences)
        assertEquals("http_500", stale.absencesError)
        assertTrue(stale.hasMessages && stale.hasAbsences)

        // The plugin was downgraded: the absences route is gone, messages still come.
        coEvery { api.schoolMessages(any()) } returns messagesOk()
        coEvery { api.schoolAbsences(any()) } returns FamilieResult.Failed("not_found", 404)
        val partly = repository.refreshInbox()
        assertTrue(partly.hasMessages)
        assertFalse(partly.hasAbsences)
    }

    @Test
    fun `without a stored copy an error still shows the view with the error`() = runBlocking {
        coEvery { api.schoolMessages(any()) } returns FamilieResult.Offline
        coEvery { api.schoolAbsences(any()) } returns FamilieResult.Failed("FORBIDDEN", 403)
        val inbox = repository.refreshInbox()
        assertNull(inbox.messages)
        assertTrue(inbox.hasMessages, "offline: the tab tells it")
        assertFalse(inbox.hasAbsences, "no access: not offered")
    }

    @Test
    fun `a fresh inbox is not loaded again within the given age`() = runBlocking {
        coEvery { api.schoolMessages(any()) } returns messagesOk()
        coEvery { api.schoolAbsences(any()) } returns absencesOk()
        repository.refreshInbox()
        coEvery { api.schoolMessages(any()) } returns FamilieResult.Offline
        assertNull(repository.refreshInbox(maxAgeMs = 60_000L).messagesError)
        assertEquals(FamilieApi.CODE_OFFLINE, repository.refreshInbox().messagesError)
    }
}
