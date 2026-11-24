package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContinueLinkTest {

    @Test
    fun parsesChatAndMailDraftLinks() {
        assertEquals(ContinueLink.Chat(42), ContinueLink.parse("gatecontrol://chat/42"))
        assertEquals(ContinueLink.MailDraft(7), ContinueLink.parse(" gatecontrol://mail/draft/7 "))
        assertEquals("gatecontrol://chat/42", ContinueLink.Chat(42).link)
        assertEquals("gatecontrol://mail/draft/7", ContinueLink.MailDraft(7).link)
    }

    @Test
    fun refusesEverythingElse() {
        assertNull(ContinueLink.parse(null))
        assertNull(ContinueLink.parse("gatecontrol://chat/"))
        assertNull(ContinueLink.parse("gatecontrol://chat/0"))
        assertNull(ContinueLink.parse("gatecontrol://chat/12a"))
        assertNull(ContinueLink.parse("gatecontrol://chat/12/x"))
        assertNull(ContinueLink.parse("gatecontrol://mail/7"))
        assertNull(ContinueLink.parse("https://gate.example.com/chat/1"))
        assertNull(ContinueLink.parse("gatecontrol://chat/1234567890123456789"))
    }

    @Test
    fun theTvPutsTheLinkOnTheLastLineAndThePhoneFindsIt() {
        val sent = ContinueLink.append("Ich hole die Pizza, bin um halb sieben da.\n", ContinueLink.Chat(5))
        assertEquals("Ich hole die Pizza, bin um halb sieben da.\n\ngatecontrol://chat/5", sent)
        val found = ContinueLink.find(sent)!!
        assertEquals(ContinueLink.Chat(5), found.target)
        assertEquals("Ich hole die Pizza, bin um halb sieben da.", found.text)
        assertEquals("Nur Text", ContinueLink.append("Nur Text", null))
    }

    @Test
    fun anOrdinaryTextHasNoLink() {
        assertNull(ContinueLink.find("Hallo\nwie geht es?"))
        assertNull(ContinueLink.find("gatecontrol://chat/5 bitte"))
        assertNull(ContinueLink.find(null))
        assertEquals(ContinueLink.InText(ContinueLink.MailDraft(3), ""), ContinueLink.find("gatecontrol://mail/draft/3"))
    }
}
