package com.gatecontrol.android.mail

import com.google.gson.JsonObject

/** Plugin id of GateControl Mail (contract C/D). */
const val MAIL_PLUGIN = "gatecontrol-mail"

/** The answer of a Mail call: never an exception (except cancellation). */
sealed class MResult<out T> {
    data class Ok<T>(val value: T) : MResult<T>()
    /** Refused or not understood: the plugin's `error.code` (or `http_<n>`), the HTTP status, the error object. */
    data class Failed(val code: String, val http: Int, val error: JsonObject? = null) : MResult<Nothing>()
    data object Offline : MResult<Nothing>()
    data object NotConfigured : MResult<Nothing>()

    fun valueOrNull(): T? = (this as? Ok)?.value
}

internal fun <T> MResult<*>.notOk(): MResult<T> = when (this) {
    is MResult.Ok -> MResult.Failed(MailApi.CODE_INVALID, 0)
    is MResult.Failed -> this
    MResult.Offline -> MResult.Offline
    MResult.NotConfigured -> MResult.NotConfigured
}

/** `GET me`: what the server offers and the person's accounts. */
data class MMe(
    val features: Set<String>,
    val limits: MLimits,
    val microsoft: MMicrosoft,
    val accounts: List<MAccount>,
    val unreadAll: Int,
    val unreadByAccount: Map<Long, Int>,
    val settings: MSettings,
) {
    fun has(feature: String): Boolean = feature in features

    fun account(id: Long?): MAccount? = id?.let { a -> accounts.firstOrNull { it.id == a } }

    companion object {
        const val FEATURE_MAIL = "mail"
        const val FEATURE_AI = "ai"
        const val FEATURE_MICROSOFT = "microsoft"
        const val FEATURE_DRIVE = "drive"
        const val FEATURE_FAMILY_CALENDAR = "familyCalendar"
        const val FEATURE_IMAGES = "images"
    }
}

data class MLimits(val accountsMax: Int, val quotaBytes: Long, val usedBytes: Long, val retentionMonths: Int)

/** Microsoft sign-in (public client with PKCE): no client id → not set up yet. */
data class MMicrosoft(val clientId: String?, val authority: String, val scopes: List<String>)

/** The person's list settings (`PUT settings`). */
data class MSettings(val threads: Boolean, val swipeLeft: String, val swipeRight: String) {
    companion object {
        const val SWIPE_ARCHIVE = "archive"
        const val SWIPE_DELETE = "delete"
        const val SWIPE_READ = "read"
        const val SWIPE_NONE = "none"
        val SWIPES = listOf(SWIPE_ARCHIVE, SWIPE_DELETE, SWIPE_READ, SWIPE_NONE)
        val DEFAULT = MSettings(threads = true, swipeLeft = SWIPE_ARCHIVE, swipeRight = SWIPE_READ)
    }
}

data class MAccount(
    val id: Long,
    /** gmx | webde | tonline | gmail | outlook | m365 | imap */
    val provider: String,
    val email: String,
    val name: String?,
    /** `#RRGGBB`, null when the server sends none or a bad one. */
    val color: String?,
    /** personal | business */
    val kind: String,
    /** ok | syncing | auth_failed | unreachable */
    val state: String,
    val errorCode: String?,
    val errorAt: Long?,
    /** off | relevant | all */
    val push: String,
    val ai: Boolean,
    /** off | suggest | auto */
    val attachments: String,
    val signature: String?,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: email

    val microsoft: Boolean get() = provider == PROVIDER_OUTLOOK || provider == PROVIDER_M365

    companion object {
        const val PROVIDER_GMX = "gmx"
        const val PROVIDER_WEBDE = "webde"
        const val PROVIDER_TONLINE = "tonline"
        const val PROVIDER_GMAIL = "gmail"
        const val PROVIDER_OUTLOOK = "outlook"
        const val PROVIDER_M365 = "m365"
        const val PROVIDER_IMAP = "imap"
        const val STATE_OK = "ok"
        const val STATE_SYNCING = "syncing"
        const val STATE_AUTH_FAILED = "auth_failed"
        const val STATE_UNREACHABLE = "unreachable"
        const val KIND_PERSONAL = "personal"
        const val KIND_BUSINESS = "business"
        val PUSH = listOf("off", "relevant", "all")
        val ATTACHMENTS = listOf("off", "suggest", "auto")
    }
}

/** `GET providers`: one entry of "Konto hinzufügen". */
data class MProvider(
    val id: String,
    val label: String,
    /** password | app_password | microsoft | imap */
    val auth: String,
    val hintDe: String?,
    val hintEn: String?,
    val help: String?,
) {
    fun hint(lang: String): String? = if (lang == "en") hintEn ?: hintDe else hintDe ?: hintEn

    companion object {
        const val AUTH_PASSWORD = "password"
        const val AUTH_APP_PASSWORD = "app_password"
        const val AUTH_MICROSOFT = "microsoft"
        const val AUTH_IMAP = "imap"
    }
}

data class MServer(val host: String, val port: Int, val security: String)

/** `POST accounts/discover`. */
data class MDiscover(val provider: String?, val auth: String?, val imap: MServer?, val smtp: MServer?, val hintDe: String?, val hintEn: String?)

data class MFolder(
    val id: Long,
    val accountId: Long,
    val name: String,
    val path: String,
    /** inbox | sent | drafts | archive | junk | trash | null */
    val role: String?,
    val parentId: Long?,
    val unread: Int,
    val total: Int,
) {
    companion object {
        const val ROLE_INBOX = "inbox"
        const val ROLE_SENT = "sent"
        const val ROLE_DRAFTS = "drafts"
        const val ROLE_ARCHIVE = "archive"
        const val ROLE_JUNK = "junk"
        const val ROLE_TRASH = "trash"
    }
}

data class MAddress(val name: String?, val email: String) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: email
}

/** A mail in a list (`Summary`). */
data class MSummary(
    val id: Long,
    val accountId: Long,
    val folderId: Long?,
    val threadId: Long?,
    val threadCount: Int,
    val from: MAddress,
    val to: List<MAddress>,
    val subject: String,
    val snippet: String,
    val date: Long,
    val unread: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val hasAttachments: Boolean,
    /** relevant | other */
    val tab: String,
    val savedToDrive: Boolean,
    val aiSummary: String?,
) {
    companion object {
        const val TAB_RELEVANT = "relevant"
        const val TAB_OTHER = "other"
        const val TAB_ALL = "all"
    }
}

data class MCounts(val relevant: Int, val other: Int)

/** `GET messages`: a page of a list. */
data class MPage(val items: List<MSummary>, val next: String?, val counts: MCounts?)

/** `GET search`. */
data class MSearchPage(val items: List<MSummary>, val next: String?, val remote: Boolean)

data class MSavedTo(val nodeId: Long, val where: String)

/** Where Drive would put an attachment. */
data class MSuggestion(
    val folderId: Long,
    val areaId: String?,
    val path: String?,
    val where: String?,
    val name: String?,
    val confidence: Double,
    /** rule | learned | ai | kind */
    val reason: String,
)

data class MAttachment(
    val id: Long,
    val name: String,
    val mime: String,
    val size: Long,
    val inline: Boolean,
    val cid: String?,
    val savedTo: MSavedTo?,
    val suggestion: MSuggestion?,
)

data class MUnsubscribe(val url: String?, val mail: String?)

/** `GET messages/<id>`: a mail to read. */
data class MMessage(
    val summary: MSummary,
    val cc: List<MAddress>,
    val bcc: List<MAddress>,
    val replyTo: List<MAddress>,
    /** Cleaned by the server; external pictures as `img/<token>`, embedded ones as `cid:`. */
    val html: String?,
    val text: String?,
    val imagesBlocked: Boolean,
    val trackers: Int,
    val attachments: List<MAttachment>,
    val unsubscribe: MUnsubscribe?,
    /** The body is not on the server (any more) and could not be fetched from the provider. */
    val bodyMissing: Boolean = false,
) {
    val id: Long get() = summary.id
}

/** `GET/POST ai/<messageId>`: "Kurz gesagt", "Zu tun", answer suggestions. */
data class MAi(val done: Boolean, val summary: String?, val todos: List<MTodo>, val replies: List<MReply>) {
    companion object {
        val NONE = MAi(done = false, summary = null, todos = emptyList(), replies = emptyList())
    }
}

data class MTodo(
    /** question | deadline | event | amount | task */
    val kind: String,
    val text: String,
    /** `YYYY-MM-DD` */
    val date: String?,
    /** `HH:mm` */
    val time: String?,
    val amount: Double?,
    val currency: String?,
) {
    companion object {
        const val QUESTION = "question"
        const val DEADLINE = "deadline"
        const val EVENT = "event"
        const val AMOUNT = "amount"
        const val TASK = "task"
    }
}

data class MReply(val label: String, val text: String)

/** One attachment of a draft as the server lists it. */
data class MDraftAttachment(val id: Long, val name: String, val size: Long, val mime: String, val asLink: Boolean, val url: String?)

data class MWarning(val kind: String, val text: String)

data class MDraft(
    val id: Long,
    val accountId: Long,
    val to: List<MAddress>,
    val cc: List<MAddress>,
    val bcc: List<MAddress>,
    val subject: String,
    val html: String,
    val attachments: List<MDraftAttachment>,
    val warnings: List<MWarning>,
)

/** An attachment to send with a draft (`Att`). */
sealed class MAtt {
    /** Already on the draft (kept when the draft is saved again). */
    data class Kept(val id: Long) : MAtt()
    /** Uploaded from the device (plugin media); the name rides along, the media itself has none ([mime] stays here). */
    data class Upload(val mediaId: String, val name: String, val mime: String) : MAtt()
    /** An attachment of the mail being forwarded. */
    data class Forwarded(val messageId: Long, val attachmentId: Long) : MAtt()
    /** A Drive file as a copy or as a link. */
    data class Drive(val nodeId: Long, val asLink: Boolean, val expiresDays: Int? = null, val password: String? = null) : MAtt()
}

/** `POST drafts/<id>/send`. */
data class MSend(val outboxId: Long, val undoUntil: Long?, val sendAt: Long?)

data class MOutboxItem(
    val id: Long,
    val draftId: Long?,
    val accountId: Long?,
    val subject: String,
    val sendAt: Long?,
    /** waiting | sending | failed */
    val state: String,
    val error: String?,
)

data class MContact(val name: String?, val email: String, val source: String)

/** "Zum Ablegen": an attachment the AI was not sure about. */
data class MFilingItem(
    val id: Long,
    val messageId: Long,
    val attachmentId: Long,
    val name: String,
    val from: MAddress?,
    val date: Long?,
    val suggestion: MSuggestion?,
)

/** `GET messages/<id>/attachments/<aid>`: where its file lies. */
data class MAttachmentMedia(val mediaId: String, val name: String, val mime: String, val size: Long)

data class MSaveAll(val saved: List<Pair<Long, MSavedTo>>, val failed: List<Pair<Long, String>>)

/** `POST messages/<id>/attachments/<aid>/drive/suggest`. */
data class MSuggest(val suggestion: MSuggestion?, val duplicate: Boolean)

/** Realtime event `mail` `{ kind: 'changed', accountId, folderId }`. */
data class MChanged(val accountId: Long?, val folderId: Long?)
