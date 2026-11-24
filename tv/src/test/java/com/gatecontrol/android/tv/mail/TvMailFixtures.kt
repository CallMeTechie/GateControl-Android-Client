package com.gatecontrol.android.tv.mail

import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MAttachment
import com.gatecontrol.android.mail.MDraft
import com.gatecontrol.android.mail.MDraftAttachment
import com.gatecontrol.android.mail.MLimits
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMessage
import com.gatecontrol.android.mail.MMicrosoft
import com.gatecontrol.android.mail.MSettings
import com.gatecontrol.android.mail.MSummary

/** Mails, accounts and drafts of the Mail tests (only example.com addresses). */
internal object TvMailFixtures {

    fun account(id: Long, email: String = "konto$id@example.com", name: String? = null, ai: Boolean = false) = MAccount(
        id = id, provider = MAccount.PROVIDER_IMAP, email = email, name = name, color = null, kind = MAccount.KIND_PERSONAL,
        state = MAccount.STATE_OK, errorCode = null, errorAt = null, push = "relevant", ai = ai, attachments = "off", signature = null,
    )

    fun me(vararg accounts: MAccount, ai: Boolean = false) = MMe(
        features = setOf(MMe.FEATURE_MAIL) + (if (ai) setOf(MMe.FEATURE_AI) else emptySet()),
        limits = MLimits(5, 0, 0, 12),
        microsoft = MMicrosoft(null, MMicrosoft.DEFAULT_AUTHORITY, emptyList()),
        accounts = accounts.toList(),
        unreadAll = 0,
        unreadByAccount = emptyMap(),
        settings = MSettings.DEFAULT,
    )

    fun summary(id: Long, account: Long = 1, date: Long = id * 1_000, unread: Boolean = false, from: String = "mila@example.com") = MSummary(
        id = id, accountId = account, folderId = 5, threadId = null, threadCount = 1,
        from = MAddress("Mila", from), to = listOf(MAddress(null, "konto$account@example.com")), subject = "Betreff $id", snippet = "Anfang $id",
        date = date, unread = unread, flagged = false, answered = false, hasAttachments = false, tab = MSummary.TAB_RELEVANT,
        savedToDrive = false, aiSummary = null,
    )

    fun message(id: Long, account: Long = 1, unread: Boolean = false, text: String? = "Hallo,\nbis morgen.", html: String? = null, attachments: List<MAttachment> = emptyList()) = MMessage(
        summary(id, account, unread = unread), emptyList(), emptyList(), emptyList(), html, text, false, 0, attachments, null,
    )

    fun attachment(id: Long, name: String, mime: String, size: Long = 1_000, inline: Boolean = false) =
        MAttachment(id, name, mime, size, inline, null, null, null)

    fun draft(id: Long, html: String = "<blockquote>Zitat</blockquote>", to: List<MAddress> = listOf(MAddress("Mila", "mila@example.com")), attachments: List<MDraftAttachment> = emptyList()) =
        MDraft(id, 1, to, emptyList(), emptyList(), "Re: Betreff", html, attachments, emptyList())
}
