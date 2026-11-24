package com.gatecontrol.android.tv.mail

import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MAi
import com.gatecontrol.android.mail.MAtt
import com.gatecontrol.android.mail.MAttachment
import com.gatecontrol.android.mail.MContact
import com.gatecontrol.android.mail.MDraft
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMessage
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailJson
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.tv.input.TvSnippet
import com.gatecontrol.android.tv.input.TvSnippetKind
import com.google.gson.JsonObject
import java.util.Locale

/** How the TV answers a mail (the Mail plugin's draft `mode`); a new mail to any address does not exist on the TV. */
enum class TvMailMode(val wire: String) {
    REPLY("reply"),
    REPLY_ALL("replyAll"),
    FORWARD("forward"),
}

/** What an attachment opens as on the TV: a photo or a PDF in the viewer of stage 2; anything else is a name only. */
enum class TvAttachmentKind { PHOTO, PDF, OTHER }

/**
 * The rules of Mail on GateControl TV (stage 3f): which accounts show, the inbox of all of them, the text of a mail
 * in large type, what an attachment opens as, the bodies of a draft and the AI's answers as building blocks. Pure
 * (no Android), unit-tested.
 */
object TvMailLogic {

    /** A mail counts as read once it was shown this long (leafing through the list marks nothing). */
    const val READ_AFTER_MS = 3_000L

    /** Mails of each account's inbox the TV loads (the newest). */
    const val PAGE = 40

    /** At most this many rows in the merged list. */
    const val MAX_LIST = 120

    /** Longest text of a mail shown (a huge newsletter stays readable, the rest is in „HTML-Ansicht“). */
    const val MAX_BODY = 30_000

    /** An attachment larger than this is not loaded onto the stick. */
    const val MAX_OPEN_BYTES = 25L * 1024 * 1024

    /** At most this many accounts are kept chosen (Mail allows a few per person). */
    private const val MAX_ACCOUNTS = 50

    private val PHOTO_MIMES = setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif", "image/bmp", "image/heic", "image/heif")
    private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val BLANK_LINES = Regex("\n{3,}")
    private val TRAILING = Regex("[ \\t\\u00A0]+\n")

    fun cleanAccounts(ids: Collection<Long>): Set<Long> = ids.filter { it > 0 }.distinct().take(MAX_ACCOUNTS).toSet()

    /** The person's accounts chosen for the TV, in the person's order (none when Mail is not there). */
    fun accounts(me: MMe?, chosen: Set<Long>): List<MAccount> = me?.accounts.orEmpty().filter { it.id in chosen }

    /**
     * The inbox of all chosen accounts: newest first (same time: the higher id), each mail once, only mails of
     * [chosen] accounts (a list loaded before an account was switched off never shows it).
     */
    fun inbox(lists: Map<Long, List<MSummary>>, chosen: Set<Long>, max: Int = MAX_LIST): List<MSummary> =
        lists.filterKeys { it in chosen }.values.flatten()
            .filter { it.accountId in chosen }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<MSummary> { it.date }.thenByDescending { it.id })
            .take(max)

    /** The query of `GET messages` for one account's inbox (both tabs, newest first). */
    fun inboxQuery(accountId: Long): Map<String, String> =
        MailLogic.listQuery(MailLogic.Box(accountId), MSummary.TAB_ALL, inbox = true, limit = PAGE)

    /** The unread mails of [list] (for the counts). */
    fun unread(list: List<MSummary>): Int = list.count { it.unread }

    /**
     * The mail's text in large type: its plain text, else its HTML as text, else the snippet. Spaces at the line ends
     * go, at most one empty line between paragraphs, at most [MAX_BODY] characters.
     */
    fun body(m: MMessage): String {
        val raw = m.text?.takeIf { it.isNotBlank() }
            ?: m.html?.takeIf { it.isNotBlank() }?.let(MailLogic::htmlToText)
            ?: m.summary.snippet
        val t = raw.replace("\r\n", "\n").replace('\r', '\n').replace(TRAILING, "\n").replace(BLANK_LINES, "\n\n").trim()
        return if (t.length <= MAX_BODY) t else t.take(MAX_BODY).trimEnd() + " …"
    }

    /** Whether „HTML-Ansicht“ (☰) is offered: the mail has HTML of its own. */
    fun hasHtml(m: MMessage): Boolean = !m.html.isNullOrBlank()

    /** The attachments shown under the text: not the pictures inside the HTML. */
    fun attachments(m: MMessage): List<MAttachment> = m.attachments.filter { !it.inline }

    fun kind(a: MAttachment): TvAttachmentKind {
        val mime = a.mime.lowercase(Locale.ROOT).substringBefore(';').trim()
        val ext = a.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mime == "application/pdf" || ext == "pdf" -> TvAttachmentKind.PDF
            mime in PHOTO_MIMES || (mime.startsWith("image/") && ext in PHOTO_EXT) || (mime == "application/octet-stream" && ext in PHOTO_EXT) ->
                TvAttachmentKind.PHOTO
            else -> TvAttachmentKind.OTHER
        }
    }

    /** Whether OK opens it: a photo or PDF, not blocked or removed by the virus scan, not too large. */
    fun canOpen(a: MAttachment): Boolean =
        kind(a) != TvAttachmentKind.OTHER && a.scan?.blocked != true && a.scan?.removed != true && a.size in 0..MAX_OPEN_BYTES

    /** Whether the AI's „Kurz gesagt“ and answers are asked for: the server has the AI and the account has it on. */
    fun aiOn(me: MMe?, accountId: Long): Boolean = me?.has(MMe.FEATURE_AI) == true && me.account(accountId)?.ai == true

    /** The AI's answer suggestions for this mail as building blocks (above the own ones). */
    fun aiSnippets(ai: MAi?): List<TvSnippet> =
        ai?.replies.orEmpty().map { it.text.trim() }.filter { it.isNotEmpty() }.distinct().map { TvSnippet(it, TvSnippetKind.AI) }

    // ─── the draft ──────────────────────────────────────────────────

    /**
     * `POST drafts` for an answer or a forward: only the mail and the mode — the server fills recipients, subject and
     * the quote in, and for a forward the attachments (it does so only when `attachments` is left out).
     */
    fun createBody(mode: TvMailMode, replyTo: Long): JsonObject = JsonObject().apply {
        addProperty("replyTo", replyTo)
        addProperty("mode", mode.wire)
    }

    /**
     * `PUT drafts/<id>` with what was written on the TV: [text] above the server's quote, the attachments the draft
     * has kept, and for a forward the recipient chosen ([to]); recipients and subject of an answer stay the server's.
     */
    fun updateBody(draft: MDraft, mode: TvMailMode, replyTo: Long, text: String, to: List<MAddress>? = null): JsonObject {
        val quote = MailLogic.splitDraft(draft.html).second
        return MailJson.draftBody(
            accountId = null,
            to = if (mode == TvMailMode.FORWARD) to else null,
            cc = null,
            bcc = null,
            subject = null,
            html = MailLogic.composeHtml(text, quote),
            replyTo = replyTo,
            mode = mode.wire,
            attachments = draft.attachments.map { MAtt.Kept(it.id) },
        )
    }

    /** What the TV wrote into a draft, read back (another round after „Rückgängig“); empty when nothing. */
    fun written(draft: MDraft): String = MailLogic.splitDraft(draft.html).first.trim()

    /** Who an answer goes to, as the server filled it in („an Mila, Jonas“). */
    fun recipients(draft: MDraft): List<String> = (draft.to + draft.cc).map { it.label }.distinct()

    /**
     * The people a mail can be forwarded to (no addresses typed on the TV): own contacts, people of this GateControl
     * and those written with lately, each address once; never a group (its members are not shown).
     */
    fun forwardTargets(contacts: List<MContact>, max: Int = 30): List<MAddress> =
        contacts.asSequence()
            .filter { !it.group && MailLogic.isEmail(it.email) }
            .map { MAddress(it.name?.trim()?.takeIf { n -> n.isNotEmpty() }, it.email.trim()) }
            .distinctBy { it.email.lowercase(Locale.ROOT) }
            .take(max)
            .toList()

    /** How long „Rückgängig“ stays after `send` (the server keeps 10 s), [callMs]: what the call took. */
    fun undoWindow(undoUntil: Long?, now: Long, callMs: Long): Long = MailLogic.undoWindow(undoUntil, now, callMs)

    /** A file name for the attachment's copy on the stick: its id and a short known extension. */
    fun cacheName(messageId: Long, a: MAttachment): String {
        val ext = a.name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            ?: if (kind(a) == TvAttachmentKind.PDF) "pdf" else "bin"
        return "m${messageId}_a${a.id}.$ext"
    }
}
