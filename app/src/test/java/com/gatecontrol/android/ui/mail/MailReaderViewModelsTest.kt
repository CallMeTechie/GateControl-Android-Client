package com.gatecontrol.android.ui.mail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MChanged
import com.gatecontrol.android.mail.MContact
import com.gatecontrol.android.mail.MLimits
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMessage
import com.gatecontrol.android.mail.MMicrosoft
import com.gatecontrol.android.mail.MPicture
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSearchPage
import com.gatecontrol.android.mail.MSettings
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailApi
import com.gatecontrol.android.mail.MailCache
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.navigation.Screen
import com.google.gson.JsonObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The reading screen after "Bilder laden" and with its conversation; the sender's page. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailReaderViewModelsTest {

    private val api: MailApi = mockk(relaxed = true)
    private val cache: MailCache = mockk(relaxed = true)
    private val repository: MailRepository = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val me = MutableStateFlow<MMe?>(null)
    private val viewModels = mutableListOf<ViewModel>()

    private fun summary(id: Long, date: Long = id * 100, from: String = "news@ionos.de", threadCount: Int = 1, unread: Boolean = false) = MSummary(
        id = id, accountId = 1, folderId = 5, threadId = if (threadCount > 1) 77 else null, threadCount = threadCount,
        from = MAddress("IONOS", from), to = emptyList(), subject = "s$id", snippet = "snip $id", date = date, unread = unread,
        flagged = false, answered = false, hasAttachments = false, tab = MSummary.TAB_RELEVANT, savedToDrive = false, aiSummary = null,
    )

    private fun message(id: Long, blocked: Boolean = false, threadCount: Int = 1) = MMessage(
        summary(id, threadCount = threadCount), emptyList(), emptyList(), emptyList(),
        "<table width=\"600\"><tr><td><img src=\"img/$id.0.sig\"></td></tr></table>", "text", blocked, 0, emptyList(), null,
    )

    private fun me(threads: Boolean = true) = MMe(
        features = setOf(MMe.FEATURE_MAIL, MMe.FEATURE_IMAGES), limits = MLimits(5, 0, 0, 12),
        microsoft = MMicrosoft(null, "https://login.microsoftonline.com/common", emptyList()), accounts = emptyList(),
        unreadAll = 0, unreadByAccount = emptyMap(), settings = MSettings.DEFAULT.copy(threads = threads),
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        every { repository.api } returns api
        every { repository.cache } returns cache
        every { repository.me } returns me
        every { repository.folders } returns MutableStateFlow(emptyMap())
        every { repository.changed } returns MutableSharedFlow<MChanged>()
        coEvery { cache.message(any()) } returns null
        every { context.getString(any()) } returns "text"
    }

    @AfterEach
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun reader(id: Long): MailReadViewModel =
        MailReadViewModel(context, repository, SavedStateHandle(mapOf(Screen.MailMessage.ARG_ID to id.toString()))).also { viewModels += it }

    // ─── "Bilder laden" ─────────────────────────────────────────────

    @Test
    fun `after Bilder laden the returned mail is shown and the page loads its pictures again at once`() {
        me.value = me()
        coEvery { api.message(1) } returns MResult.Ok(message(1, blocked = true) to JsonObject())
        coEvery { api.allowImages(1, false) } returns MResult.Ok(message(1, blocked = false) to JsonObject())
        val vm = reader(1)
        val before = vm.state.value
        assertTrue(before.message!!.imagesBlocked)

        vm.allowImages(sender = false)

        val after = vm.state.value
        assertFalse(after.message!!.imagesBlocked, "the server's answer is the state")
        assertFalse(after.imagesBusy)
        // the same HTML as before (the pictures' addresses do not change): only a new round makes the WebView load again
        assertEquals(before.message!!.html, after.message!!.html)
        assertEquals(before.pictureRound + 1, after.pictureRound)
        coVerify { cache.putMessage(any(), any()) }
    }

    @Test
    fun `a picture refused while blocked is not kept - it is asked for again after Bilder laden`() {
        me.value = me()
        coEvery { api.message(1) } returns MResult.Ok(message(1, blocked = true) to JsonObject())
        coEvery { api.picture("img/1.0.sig") } returnsMany listOf(MPicture.None, MPicture.Got(byteArrayOf(1, 2), "image/png"))
        val vm = reader(1)
        assertNull(runBlocking { vm.picture("img/1.0.sig") })
        assertEquals("image/png", runBlocking { vm.picture("img/1.0.sig") }!!.second)
        coVerify(exactly = 2) { api.picture("img/1.0.sig") }
    }

    @Test
    fun `Bilder laden refused - a toast, the mail stays as it was`() {
        me.value = me()
        coEvery { api.message(1) } returns MResult.Ok(message(1, blocked = true) to JsonObject())
        coEvery { api.allowImages(1, true) } returns MResult.Failed("images_off", 409)
        val vm = reader(1)
        vm.allowImages(sender = true)
        assertTrue(vm.state.value.message!!.imagesBlocked)
        assertEquals(0, vm.state.value.pictureRound)
        assertTrue(vm.event.value is MailReadEvent.Toast)
    }

    // ─── the conversation ───────────────────────────────────────────

    @Test
    fun `the conversation loads with the mail, oldest first, and opens on a tap`() {
        me.value = me(threads = true)
        coEvery { api.message(2) } returns MResult.Ok(message(2, threadCount = 3) to JsonObject())
        coEvery { api.thread(77) } returns MResult.Ok(listOf(message(1, threadCount = 3), message(2, threadCount = 3), message(3, threadCount = 3)))
        val vm = reader(2)
        assertEquals(listOf(1L, 2L, 3L), vm.state.value.conversation.map { it.id })
        assertFalse(vm.state.value.threadOpen, "folded until tapped")

        vm.toggleThread()
        assertTrue(vm.state.value.threadOpen)
        vm.toggleThreadMail(1)
        assertEquals(setOf(1L), vm.state.value.threadUnfolded)
        vm.toggleThreadMail(1)
        assertTrue(vm.state.value.threadUnfolded.isEmpty())
        vm.toggleThread()
        assertFalse(vm.state.value.threadOpen)
        coVerify(exactly = 1) { api.thread(77) }
    }

    @Test
    fun `without grouping the conversation loads only when tapped`() {
        me.value = me(threads = false)
        coEvery { api.message(2) } returns MResult.Ok(message(2, threadCount = 2) to JsonObject())
        coEvery { api.thread(77) } returns MResult.Ok(listOf(message(1, threadCount = 2), message(2, threadCount = 2)))
        val vm = reader(2)
        assertTrue(vm.state.value.conversation.isEmpty())
        coVerify(exactly = 0) { api.thread(any()) }
        vm.toggleThread()
        assertEquals(listOf(1L, 2L), vm.state.value.conversation.map { it.id })
        assertFalse(vm.state.value.threadLoading)
    }

    @Test
    fun `a single mail has no conversation to load`() {
        me.value = me()
        coEvery { api.message(2) } returns MResult.Ok(message(2) to JsonObject())
        val vm = reader(2)
        vm.toggleThread()
        coVerify(exactly = 0) { api.thread(any()) }
    }

    // ─── the sender's page ──────────────────────────────────────────

    private fun sender(email: String = "news@ionos.de", name: String? = "IONOS", account: Long? = 1): MailSenderViewModel =
        MailSenderViewModel(
            repository,
            SavedStateHandle(
                mapOfNotNull(
                    Screen.MailSender.ARG_EMAIL to email,
                    Screen.MailSender.ARG_NAME to name,
                    Screen.MailSender.ARG_ACCOUNT to account?.toString(),
                ),
            ),
        ).also { viewModels += it }

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, Any?> = pairs.filter { it.second != null }.toMap()

    @Test
    fun `the sender's mails in the account, page by page, with the contact's name`() {
        coEvery { api.contacts("news@ionos.de") } returns MResult.Ok(listOf(MContact("IONOS Kundenservice", "news@ionos.de", MContact.SOURCE_PERSON)))
        coEvery { api.search(mapOf("from" to "news@ionos.de", "box" to "1")) } returns
            MResult.Ok(MSearchPage(listOf(summary(9), summary(8, from = "othernews@ionos.de")), "c-8", false))
        coEvery { api.search(mapOf("from" to "news@ionos.de", "box" to "1", "cursor" to "c-8")) } returns
            MResult.Ok(MSearchPage(listOf(summary(7), summary(9)), null, false))
        val vm = sender()
        val s = vm.state.value
        assertEquals("IONOS Kundenservice", s.label, "the contacts' name first")
        assertEquals(listOf(9L), s.items.map { it.id }, "exactly this address")
        assertEquals("c-8", s.next)
        assertFalse(s.loading)

        vm.more()
        assertEquals(listOf(9L, 7L), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.next)
        vm.more()
        coVerify(exactly = 2) { api.search(any()) }
    }

    @Test
    fun `without a contact the mail's name, without that the address`() {
        coEvery { api.contacts(any()) } returns MResult.Ok(listOf(MContact("Recent", "news@ionos.de", MContact.SOURCE_RECENT)))
        coEvery { api.search(any()) } returns MResult.Ok(MSearchPage(emptyList(), null, false))
        assertEquals("IONOS", sender().state.value.label)
        assertEquals("news@ionos.de", sender(name = null).state.value.label)
        assertNull(sender().state.value.contact)
    }

    @Test
    fun `no address - nothing is asked`() {
        val vm = sender(email = "kein-mail")
        assertEquals("not_found", vm.state.value.error)
        coVerify(exactly = 0) { api.search(any()) }
    }
}
