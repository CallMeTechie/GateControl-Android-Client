package com.gatecontrol.android.tv.chat

import com.gatecontrol.android.chat.ChatApi
import com.gatecontrol.android.chat.ChatConversationList
import com.gatecontrol.android.chat.ChatEvent
import com.gatecontrol.android.chat.ChatMessagePage
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatSuggestion
import com.gatecontrol.android.tv.chat.TvChatFixtures.bridge
import com.gatecontrol.android.tv.chat.TvChatFixtures.conversation
import com.gatecontrol.android.tv.chat.TvChatFixtures.message
import com.gatecontrol.android.tv.chat.TvChatFixtures.messageJson
import com.gatecontrol.android.tv.zuhause.TvSourceState
import com.google.gson.JsonObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The TV's slim chat repository against the messenger: no outbox, „Erneut versuchen“ with the same clientId. */
@OptIn(ExperimentalCoroutinesApi::class)
class TvChatsTest {

    private val api = mockk<ChatApi>(relaxed = true)
    private val chats = TvChats(api).apply { scope = CoroutineScope(UnconfinedTestDispatcher()) }
    private val bodies = mutableListOf<JsonObject>()

    private fun page(vararg ids: Long) = ChatResult.Ok(ChatMessagePage(ids.map { message(it) }, hasMore = false, readUpTo = null, deliveredUpTo = null, receipts = emptyList()))

    private fun listed(vararg c: com.gatecontrol.android.chat.ChatConversation) = runBlocking {
        coEvery { api.me() } returns ChatResult.Offline
        coEvery { api.conversations(null) } returns ChatResult.Ok(ChatConversationList(c.toList(), emptyList(), null))
        chats.refresh(force = true)
    }

    @Test
    fun `the list loads, offline keeps what is there`() = runBlocking {
        listed(conversation(5, "Familie", managedBy = "gatecontrol-familie"))
        assertEquals(TvSourceState.OK, chats.data.value.state)
        coEvery { api.conversations(null) } returns ChatResult.Offline
        chats.refresh(force = true)
        assertEquals(TvSourceState.OFFLINE, chats.data.value.state)
        assertEquals(1, chats.data.value.conversations.size)
    }

    @Test
    fun `a sent text is stored, its clientId and reply go along`() = runBlocking {
        listed(conversation(5, "Familie"))
        coEvery { api.send(5, capture(bodies)) } returns ChatResult.Ok(message(40, mine = true, clientId = "tv0123456789abcdef", text = "Bin da"))
        assertTrue(chats.send(5, " Bin da ", replyTo = 39, clientId = "tv0123456789abcdef"))
        assertEquals("tv0123456789abcdef", bodies.single().get("clientId").asString)
        assertEquals("Bin da", bodies.single().get("text").asString)
        assertEquals(39L, bodies.single().get("replyTo").asLong)
        assertTrue(chats.data.value.pending.isEmpty())
        assertEquals(listOf(40L), chats.data.value.messages[5]!!.map { it.id })
    }

    @Test
    fun `not sent stays with its reason, the retry keeps the clientId`() = runBlocking {
        listed(conversation(5, "Familie"))
        coEvery { api.send(5, capture(bodies)) } returns ChatResult.Offline andThen ChatResult.Ok(message(41, mine = true, clientId = "tvfirstclientid00", text = "Ok"))
        assertFalse(chats.send(5, "Ok", clientId = "tvfirstclientid00"))
        val p = chats.data.value.pending.single()
        assertEquals(TvChatSendError.OFFLINE, p.error)
        assertTrue(chats.retry(p.clientId))
        assertEquals(listOf("tvfirstclientid00", "tvfirstclientid00"), bodies.map { it.get("clientId").asString })
        assertTrue(chats.data.value.pending.isEmpty())
    }

    @Test
    fun `a bridge that takes only text, and one that is down`() = runBlocking {
        listed(conversation(7, "Oma", bridge = bridge()), conversation(8, "Opa", bridge = bridge(state = "unreachable")))
        coEvery { api.send(7, any()) } returns ChatResult.Failed("bridge_text_only", 409)
        assertFalse(chats.send(7, "Hallo", clientId = "tvtextonly0000000"))
        assertEquals(TvChatSendError.TEXT_ONLY, chats.data.value.pending.single().error)
        // a bridge that is not ok: nothing is even tried (the server takes nothing, there is no queue)
        assertFalse(chats.send(8, "Hallo"))
        coVerify(exactly = 0) { api.send(8, any()) }
    }

    @Test
    fun `read receipts only for the chat really on the screen`() = runBlocking {
        listed(conversation(5, "Familie", unread = 2))
        coEvery { api.messages(5, limit = TvChatLogic.PAGE) } returns page(1, 2)
        coEvery { api.read(5, any()) } returns ChatResult.Ok(0)
        chats.load(5)
        coVerify(exactly = 0) { api.read(any(), any()) }
        chats.setReading(5)
        coVerify(exactly = 1) { api.read(5, 2) }
        assertEquals(0, chats.data.value.conversations.single().unread)
        chats.setReading(null)
        coEvery { api.conversation(5) } returns ChatResult.Ok(conversation(5, "Familie", unread = 1, lastMessage = messageJson(3, 5)))
        coEvery { api.messages(5, limit = TvChatLogic.PAGE) } returns page(1, 2, 3)
        chats.onEvent(ChatEvent.MessageNew(5, 3, 2, null))
        coVerify(exactly = 0) { api.read(5, 3) }
    }

    @Test
    fun `message new loads the chat and the open history load, others' messages arrive for the hint`() = runBlocking {
        listed(conversation(5, "Familie"))
        coEvery { api.messages(5, limit = TvChatLogic.PAGE) } returns page(1)
        chats.load(5)
        coEvery { api.conversation(5) } returns ChatResult.Ok(conversation(5, "Familie", unread = 1, lastMessage = messageJson(2, 5)))
        coEvery { api.messages(5, limit = TvChatLogic.PAGE) } returns page(1, 2)
        val got = CompletableDeferred<TvChatArrival>()
        CoroutineScope(UnconfinedTestDispatcher()).launch { got.complete(chats.arrivals.first()) }
        chats.onEvent(ChatEvent.MessageNew(5, 2, 2, null))
        assertEquals(TvChatArrival(5, 2), withTimeout(5_000) { got.await() })
        assertEquals(listOf(1L, 2L), chats.data.value.messages[5]!!.map { it.id })
        assertEquals(1, chats.data.value.conversations.single().unread)
    }

    @Test
    fun `quick answers and reactions`() = runBlocking {
        listed(conversation(5, "Familie"))
        coEvery { api.suggest(9) } returns ChatResult.Ok(ChatSuggestion(listOf("Ja", "Später"), emptyList()))
        assertEquals(listOf("Ja", "Später"), chats.suggestions(9))
        coEvery { api.suggest(10) } returns ChatResult.Offline
        assertEquals(emptyList<String>(), chats.suggestions(10))
        coEvery { api.react(9, "👍") } returns ChatResult.Ok(emptyList())
        assertTrue(chats.react(message(9), "👍"))
    }
}
