package com.gatecontrol.android.tv.mail

import android.content.Context
import com.gatecontrol.android.mail.MChanged
import com.gatecontrol.android.mail.MailApi
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The live event `mail` reaches the TV only for the accounts it shows; calendar and contacts never. */
@OptIn(ExperimentalCoroutinesApi::class)
class TvMailEventsTest {

    private val chosen = MutableStateFlow(setOf(1L))
    private val settings = mockk<TvMailSettings>(relaxed = true) { every { accounts } returns chosen }
    private val mail = TvMail(mockk<MailApi>(relaxed = true), settings, mockk<Context>(relaxed = true))
    private val events = TvMailEvents { mail }

    private fun json(s: String) = JsonParser.parseString(s)

    @Test
    fun `only events of a chosen account count`() = runTest {
        val got = mutableListOf<MChanged>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { mail.changes.collect { got += it } }
        events.onEvent("mail", json("""{"kind":"changed","accountId":2,"folderId":5}"""))
        events.onEvent("calendar", json("""{"kind":"changed","accountId":1}"""))
        events.onEvent("mail", json("""{"kind":"changed","accountId":1,"folderId":5,"messageId":7}"""))
        assertEquals(listOf(7L), got.map { it.messageId })
        chosen.value = emptySet()
        events.onEvent("mail", json("""{"kind":"changed","accountId":1}"""))
        assertEquals(1, got.size)
        assertEquals(com.gatecontrol.android.mail.MAIL_PLUGIN, events.plugin)
    }
}
