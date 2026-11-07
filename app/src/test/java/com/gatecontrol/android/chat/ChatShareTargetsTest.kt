package com.gatecontrol.android.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChatShareTargetsTest {

    @Test
    fun `GateDrop on the server - the entry is GateDrop, with or without chats`() {
        assertEquals(ChatShareTarget.GATEDROP, ChatShareTargets.choose(gatedrop = true, chats = true))
        assertEquals(ChatShareTarget.GATEDROP, ChatShareTargets.choose(gatedrop = true, chats = false))
    }

    @Test
    fun `only the chats - the entry is the chat one`() {
        assertEquals(ChatShareTarget.CHAT, ChatShareTargets.choose(gatedrop = false, chats = true))
    }

    @Test
    fun `neither - no entry`() {
        assertNull(ChatShareTargets.choose(gatedrop = false, chats = false))
    }

    @Test
    fun `aliases are distinct components of the app's namespace`() {
        val aliases = ChatShareTarget.entries.map { it.alias }
        assertEquals(aliases.size, aliases.toSet().size)
        aliases.forEach { check(it.startsWith("com.gatecontrol.android.chat.")) { it } }
    }
}
