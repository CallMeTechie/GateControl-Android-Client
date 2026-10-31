package com.gatecontrol.android.ui.mail

import com.gatecontrol.android.mail.MLimits
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMicrosoft
import com.gatecontrol.android.mail.MSettings
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailDrawerFootTest {

    private fun me(vararg features: String) = MMe(
        features = features.toSet(),
        limits = MLimits(5, 0, 0, 12),
        microsoft = MMicrosoft(null, "https://login.microsoftonline.com/common", emptyList()),
        accounts = emptyList(),
        unreadAll = 0,
        unreadByAccount = emptyMap(),
        settings = MSettings.DEFAULT,
    )

    @Test
    fun `contacts and calendar only with the server's features`() {
        assertFalse(MailDrawerFoot.calendar(null))
        assertFalse(MailDrawerFoot.contacts(null))
        assertFalse(MailDrawerFoot.calendar(me("mail")))
        assertFalse(MailDrawerFoot.contacts(me("mail", "calendar")))
        assertTrue(MailDrawerFoot.calendar(me("mail", "calendar")))
        assertTrue(MailDrawerFoot.contacts(me("mail", "contacts")))
    }
}
