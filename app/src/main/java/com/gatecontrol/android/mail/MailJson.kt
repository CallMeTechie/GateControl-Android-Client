package com.gatecontrol.android.mail

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The Mail plugin's answers (contract C2), read from the Gson tree (pure, unit-tested): never by reflection, so R8
 * cannot change what is read. Unknown fields are ignored, wrong ones make the entry (or the answer) null; a list keeps
 * the entries it can read. Also the request bodies the app sends.
 */
object MailJson {

    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private val TOKEN = Regex("^[A-Za-z0-9_.~-]{1,200}$")
    private val CODE = Regex("^[a-z0-9_]{1,64}$")

    // ─── helpers ────────────────────────────────────────────────────

    private fun JsonObject.el(key: String): JsonElement? = get(key)?.takeIf { it !is JsonNull }

    internal fun JsonObject.str(key: String): String? =
        el(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun JsonObject.long(key: String): Long? = el(key)?.takeIf { it.isJsonPrimitive }?.let { p ->
        val prim = p.asJsonPrimitive
        when {
            prim.isNumber -> runCatching { prim.asDouble }.getOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }?.toLong()
            prim.isString -> prim.asString.trim().toLongOrNull()
            else -> null
        }
    }

    private fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun JsonObject.double(key: String): Double? = el(key)?.takeIf { it.isJsonPrimitive }?.let { p ->
        val prim = p.asJsonPrimitive
        when {
            prim.isNumber -> prim.asDouble
            prim.isString -> prim.asString.trim().toDoubleOrNull()
            else -> null
        }
    }?.takeIf { !it.isNaN() && !it.isInfinite() }

    private fun JsonObject.bool(key: String): Boolean? = el(key)?.takeIf { it.isJsonPrimitive }?.let { p ->
        val prim = p.asJsonPrimitive
        when {
            prim.isBoolean -> prim.asBoolean
            prim.isNumber -> prim.asInt != 0
            prim.isString -> when (prim.asString) { "true", "1" -> true; "false", "0" -> false; else -> null }
            else -> null
        }
    }

    internal fun JsonObject.obj(key: String): JsonObject? = el(key)?.takeIf { it.isJsonObject }?.asJsonObject

    internal fun JsonObject.arr(key: String): JsonArray? = el(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject }

    private fun JsonArray.strings(): List<String> = mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }

    /** A positive id (number or numeric string). */
    private fun JsonObject.id(key: String): Long? = long(key)?.takeIf { it > 0 }

    /** An opaque cursor (`next`): a string or a number, as the server sends it. */
    private fun JsonObject.cursor(key: String): String? =
        el(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() && it.length <= 512 }

    fun parse(raw: String?): JsonObject? =
        raw?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }?.takeIf { it.isJsonObject }?.asJsonObject

    // ─── errors ─────────────────────────────────────────────────────

    /**
     * The code of a refusal: the plugin's `{ error: { code } }`, the core's `{ ok: false, code }`, else `http_<n>`
     * (0: no answer at all).
     */
    fun failureCode(http: Int, body: JsonObject?): String {
        val code = body?.obj("error")?.str("code") ?: body?.str("error")?.takeIf { CODE.matches(it) } ?: body?.str("code")
        return code?.takeIf { CODE.matches(it) } ?: "http_$http"
    }

    /** A 2xx answer that still says no (`{ error: { code } }` or `{ ok: false }`). */
    fun isRefusal(body: JsonObject): Boolean = body.obj("error") != null || body.bool("ok") == false

    /** The `hint` of a refusal (`409 auth_failed`): `{ de, en }` or a plain text. */
    fun errorHint(body: JsonObject?, lang: String): String? {
        val err = body?.obj("error") ?: body ?: return null
        err.str("hint")?.let { return it.takeIf { h -> h.isNotBlank() } }
        val h = err.obj("hint") ?: return null
        return (if (lang == "en") h.str("en") ?: h.str("de") else h.str("de") ?: h.str("en"))?.takeIf { it.isNotBlank() }
    }

    // ─── me, accounts, providers ────────────────────────────────────

    fun me(o: JsonObject): MMe? {
        val features = o.arr("features")?.strings()?.toSet() ?: return null
        val limits = o.obj("limits")
        val ms = o.obj("microsoft")
        val unread = o.obj("unread")
        val by = unread?.obj("byAccount")?.entrySet()?.mapNotNull { (k, v) ->
            val id = k.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val n = v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: return@mapNotNull null
            id to n.coerceAtLeast(0)
        }?.toMap().orEmpty()
        return MMe(
            features = features,
            limits = MLimits(
                accountsMax = limits?.int("accountsMax") ?: 5,
                quotaBytes = limits?.long("quotaBytes") ?: 0L,
                usedBytes = limits?.long("usedBytes") ?: 0L,
                retentionMonths = limits?.int("retentionMonths") ?: 12,
            ),
            microsoft = MMicrosoft(
                clientId = ms?.str("clientId")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 },
                authority = ms?.str("authority")?.trimEnd('/')?.takeIf { it.startsWith("https://") } ?: MailMicrosoft.DEFAULT_AUTHORITY,
                scopes = ms?.arr("scopes")?.strings()?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: MailMicrosoft.DEFAULT_SCOPES,
            ),
            accounts = o.arr("accounts")?.objects()?.mapNotNull(::account).orEmpty(),
            unreadAll = unread?.int("all")?.coerceAtLeast(0) ?: 0,
            unreadByAccount = by,
            settings = o.obj("settings")?.let(::settings) ?: MSettings.DEFAULT,
        )
    }

    fun settings(o: JsonObject): MSettings = MSettings(
        threads = o.bool("threads") ?: MSettings.DEFAULT.threads,
        swipeLeft = o.str("swipeLeft")?.takeIf { it in MSettings.SWIPES } ?: MSettings.DEFAULT.swipeLeft,
        swipeRight = o.str("swipeRight")?.takeIf { it in MSettings.SWIPES } ?: MSettings.DEFAULT.swipeRight,
    )

    /** `{ settings }` of `PUT settings`. */
    fun settingsAnswer(o: JsonObject): MSettings? = o.obj("settings")?.let(::settings)

    fun account(o: JsonObject): MAccount? {
        val id = o.id("id") ?: return null
        val email = o.str("email")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val error = o.obj("error")
        return MAccount(
            id = id,
            provider = o.str("provider") ?: MAccount.PROVIDER_IMAP,
            email = email,
            name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() },
            color = o.str("color")?.takeIf { COLOR.matches(it) },
            kind = o.str("kind")?.takeIf { it == MAccount.KIND_BUSINESS } ?: MAccount.KIND_PERSONAL,
            state = o.str("state") ?: MAccount.STATE_OK,
            errorCode = error?.str("code"),
            errorAt = error?.long("at"),
            push = o.str("push")?.takeIf { it in MAccount.PUSH } ?: "all",
            ai = o.bool("ai") ?: false,
            attachments = o.str("attachments")?.takeIf { it in MAccount.ATTACHMENTS } ?: "off",
            signature = o.str("signature")?.takeIf { it.isNotBlank() },
            avatar = o.obj("avatar")?.let { av ->
                val media = av.str("mediaId")?.takeIf { TOKEN.matches(it) } ?: return@let null
                val mime = av.str("mime")?.lowercase()?.substringBefore(';')?.trim()?.takeIf { PICTURE_MIME.matches(it) } ?: return@let null
                MAvatar(media, mime)
            },
        )
    }

    /** `{ account }`. */
    fun accountAnswer(o: JsonObject): MAccount? = o.obj("account")?.let(::account)

    /** `GET providers`: an array, or `{ items }` / `{ providers }`. */
    fun providers(root: JsonElement): List<MProvider>? {
        val arr = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.let { it.arr("providers") ?: it.arr("items") }
            else -> null
        } ?: return null
        return arr.objects().mapNotNull { o ->
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val hint = o.obj("hint")
            MProvider(
                id = id,
                label = o.str("label")?.takeIf { it.isNotBlank() } ?: id,
                auth = o.str("auth") ?: MProvider.AUTH_PASSWORD,
                hintDe = hint?.str("de")?.takeIf { it.isNotBlank() },
                hintEn = hint?.str("en")?.takeIf { it.isNotBlank() },
                help = o.str("help")?.takeIf { it.startsWith("https://") },
            )
        }
    }

    private fun server(o: JsonObject?): MServer? {
        o ?: return null
        val host = o.str("host")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = o.int("port")?.takeIf { it in 1..65535 } ?: return null
        return MServer(host, port, o.str("security") ?: "tls")
    }

    fun discover(o: JsonObject): MDiscover {
        val hint = o.obj("hint")
        return MDiscover(
            provider = o.str("provider"),
            auth = o.str("auth"),
            imap = server(o.obj("imap")),
            smtp = server(o.obj("smtp")),
            hintDe = hint?.str("de") ?: o.str("hint"),
            hintEn = hint?.str("en"),
        )
    }

    // ─── folders, lists ─────────────────────────────────────────────

    /** `GET folders` → the folders per account, in the server's order. */
    fun folders(o: JsonObject): Map<Long, List<MFolder>>? {
        val accounts = o.arr("accounts") ?: return null
        val out = LinkedHashMap<Long, List<MFolder>>()
        for (a in accounts.objects()) {
            val accountId = a.id("accountId") ?: continue
            out[accountId] = a.arr("folders")?.objects()?.mapNotNull { f ->
                val id = f.id("id") ?: return@mapNotNull null
                MFolder(
                    id = id,
                    accountId = accountId,
                    name = f.str("name")?.takeIf { it.isNotBlank() } ?: f.str("path") ?: return@mapNotNull null,
                    path = f.str("path") ?: "",
                    role = f.str("role")?.takeIf { it in ROLES },
                    parentId = f.id("parentId"),
                    unread = f.int("unread")?.coerceAtLeast(0) ?: 0,
                    total = f.int("total")?.coerceAtLeast(0) ?: 0,
                )
            }.orEmpty()
        }
        return out
    }

    private val ROLES = setOf(MFolder.ROLE_INBOX, MFolder.ROLE_SENT, MFolder.ROLE_DRAFTS, MFolder.ROLE_ARCHIVE, MFolder.ROLE_JUNK, MFolder.ROLE_TRASH)

    fun address(o: JsonObject): MAddress? {
        val email = (o.str("email") ?: o.str("address"))?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return MAddress(o.str("name")?.trim()?.takeIf { it.isNotEmpty() }, email)
    }

    /** Addresses as objects `{ name, email }` or as plain strings (`Name <a@b>` or `a@b`). */
    fun addresses(arr: JsonArray?): List<MAddress> = arr?.mapNotNull { e ->
        when {
            e.isJsonObject -> address(e.asJsonObject)
            e.isJsonPrimitive -> MailLogic.parseAddress(e.asString)
            else -> null
        }
    }.orEmpty()

    fun summary(o: JsonObject): MSummary? {
        val id = o.id("id") ?: return null
        val accountId = o.id("accountId") ?: return null
        val from = o.obj("from")?.let(::address) ?: MAddress(o.obj("from")?.str("name"), "")
        return MSummary(
            id = id,
            accountId = accountId,
            folderId = o.id("folderId"),
            threadId = o.id("threadId"),
            threadCount = o.int("threadCount")?.coerceAtLeast(1) ?: 1,
            from = from,
            to = addresses(o.arr("to")),
            subject = o.str("subject") ?: "",
            snippet = o.str("snippet")?.take(400) ?: "",
            date = o.long("date") ?: 0L,
            unread = o.bool("unread") ?: false,
            flagged = o.bool("flagged") ?: false,
            answered = o.bool("answered") ?: false,
            hasAttachments = o.bool("hasAttachments") ?: false,
            tab = o.str("tab")?.takeIf { it == MSummary.TAB_OTHER } ?: MSummary.TAB_RELEVANT,
            savedToDrive = o.bool("savedToDrive") ?: false,
            aiSummary = o.obj("ai")?.str("summary")?.takeIf { it.isNotBlank() },
        )
    }

    fun summaries(arr: JsonArray?): List<MSummary> = arr?.objects()?.mapNotNull(::summary).orEmpty()

    fun page(o: JsonObject): MPage? {
        val items = o.arr("items") ?: return null
        val counts = o.obj("counts")?.let { c -> MCounts(c.int("relevant")?.coerceAtLeast(0) ?: 0, c.int("other")?.coerceAtLeast(0) ?: 0) }
        return MPage(summaries(items), o.cursor("next"), counts)
    }

    fun searchPage(o: JsonObject): MSearchPage? {
        val items = o.arr("items") ?: return null
        return MSearchPage(summaries(items), o.cursor("next"), o.bool("remote") ?: false)
    }

    /** `{ ok, items? }` of the bulk actions: the changed summaries (empty when the server sends none). */
    fun changedItems(o: JsonObject): List<MSummary> = summaries(o.arr("items"))

    /** `GET threads/<id>` → oldest first. */
    fun thread(o: JsonObject): List<MMessage>? = o.arr("items")?.objects()?.mapNotNull(::message)

    // ─── one message ────────────────────────────────────────────────

    fun suggestion(o: JsonObject?): MSuggestion? {
        o ?: return null
        val folderId = o.id("folderId") ?: return null
        return MSuggestion(
            folderId = folderId,
            areaId = o.str("areaId"),
            path = o.str("path")?.takeIf { it.isNotBlank() },
            where = o.str("where")?.takeIf { it.isNotBlank() },
            name = o.str("name")?.takeIf { it.isNotBlank() },
            confidence = (o.double("confidence") ?: 0.0).coerceIn(0.0, 1.0),
            reason = o.str("reason") ?: "ai",
        )
    }

    fun savedTo(o: JsonObject?): MSavedTo? {
        o ?: return null
        val node = o.id("nodeId") ?: return null
        return MSavedTo(node, o.str("where") ?: "")
    }

    fun attachment(o: JsonObject): MAttachment? {
        val id = o.id("id") ?: return null
        return MAttachment(
            id = id,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: "attachment",
            mime = o.str("mime")?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            size = o.long("size")?.coerceAtLeast(0) ?: 0L,
            inline = o.bool("inline") ?: false,
            cid = o.str("cid")?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotEmpty() },
            savedTo = savedTo(o.obj("savedTo")),
            suggestion = suggestion(o.obj("suggestion")),
        )
    }

    fun message(o: JsonObject): MMessage? {
        val summary = summary(o) ?: return null
        val unsub = o.obj("listUnsubscribe")?.let { u ->
            MUnsubscribe(u.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }, u.str("mail")?.takeIf { it.isNotBlank() })
        }?.takeIf { it.url != null || it.mail != null }
        return MMessage(
            summary = summary,
            cc = addresses(o.arr("cc")),
            bcc = addresses(o.arr("bcc")),
            replyTo = addresses(o.arr("replyTo")),
            html = o.str("html")?.takeIf { it.isNotBlank() },
            text = o.str("text"),
            imagesBlocked = o.bool("imagesBlocked") ?: false,
            trackers = o.int("trackers")?.coerceAtLeast(0) ?: 0,
            attachments = o.arr("attachments")?.objects()?.mapNotNull(::attachment).orEmpty(),
            unsubscribe = unsub,
            bodyMissing = o.bool("bodyMissing") ?: false,
        )
    }

    /** `{ mediaId, name, mime, size }`. */
    fun attachmentMedia(o: JsonObject): MAttachmentMedia? {
        val media = o.str("mediaId")?.takeIf { TOKEN.matches(it) } ?: return null
        return MAttachmentMedia(media, o.str("name") ?: "attachment", o.str("mime") ?: "application/octet-stream", o.long("size") ?: 0L)
    }

    /** A picture of the HTML: inline bytes (`img/…`) or a media file (`cid/…`). */
    class Picture(val mime: String, val data: ByteArray?, val mediaId: String?)

    /** Raster pictures only: SVG (it can carry script) and anything else is refused. */
    private val PICTURE_MIME = Regex("^image/(png|jpe?g|gif|webp|bmp|x-icon|vnd\\.microsoft\\.icon|avif|heic|heif)$")

    /**
     * `GET img/<token>` → `{ mime, size, data }` (Base64) or `GET cid/<m>/<a>` → `{ mediaId, mime, size, url }`; null
     * when it is no picture the WebView may show.
     */
    fun picture(o: JsonObject): Picture? {
        val mime = o.str("mime")?.lowercase()?.substringBefore(';')?.trim()?.takeIf { PICTURE_MIME.matches(it) } ?: return null
        val data = o.str("data")?.let { runCatching { java.util.Base64.getMimeDecoder().decode(it) }.getOrNull() }
        if (data != null) return if (data.isEmpty() || data.size > 6 * 1024 * 1024) null else Picture(mime, data, null)
        val media = o.str("mediaId")?.takeIf { TOKEN.matches(it) } ?: return null
        return Picture(mime, null, media)
    }

    // ─── AI ─────────────────────────────────────────────────────────

    fun ai(o: JsonObject): MAi? {
        val status = o.str("status") ?: return null
        if (status != "done") return MAi.NONE
        return MAi(
            done = true,
            summary = o.str("summary")?.takeIf { it.isNotBlank() },
            todos = o.arr("todos")?.objects()?.mapNotNull { t ->
                val text = t.str("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MTodo(
                    kind = t.str("kind") ?: MTodo.TASK,
                    text = text,
                    date = t.str("date")?.takeIf { MailLogic.isIsoDate(it) },
                    time = t.str("time")?.takeIf { MailLogic.isTime(it) },
                    amount = t.double("amount"),
                    currency = t.str("currency"),
                )
            }.orEmpty(),
            replies = o.arr("replies")?.objects()?.mapNotNull { r ->
                val text = r.str("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MReply(r.str("label")?.takeIf { it.isNotBlank() } ?: text.take(40), text)
            }.orEmpty(),
        )
    }

    /** `{ where }` of a to-do put into the family calendar. */
    fun where(o: JsonObject): String? = o.str("where") ?: ""

    // ─── drafts, sending ────────────────────────────────────────────

    fun draft(o: JsonObject): MDraft? {
        val id = o.id("id") ?: return null
        return MDraft(
            id = id,
            accountId = o.id("accountId") ?: 0L,
            to = addresses(o.arr("to")),
            cc = addresses(o.arr("cc")),
            bcc = addresses(o.arr("bcc")),
            subject = o.str("subject") ?: "",
            html = o.str("html") ?: "",
            attachments = o.arr("attachments")?.objects()?.mapNotNull { a ->
                val aid = a.id("id") ?: return@mapNotNull null
                MDraftAttachment(
                    id = aid,
                    name = a.str("name") ?: "attachment",
                    size = a.long("size")?.coerceAtLeast(0) ?: 0L,
                    mime = a.str("mime") ?: "application/octet-stream",
                    asLink = a.str("as") == "link",
                    url = a.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                )
            }.orEmpty(),
            warnings = o.arr("warnings")?.objects()?.mapNotNull { w ->
                val kind = w.str("kind") ?: return@mapNotNull null
                MWarning(kind, w.str("text") ?: "")
            }.orEmpty(),
        )
    }

    /** `{ draft }`. */
    fun draftAnswer(o: JsonObject): MDraft? = o.obj("draft")?.let(::draft) ?: draft(o)

    fun send(o: JsonObject): MSend? {
        val id = o.id("outboxId") ?: return null
        return MSend(id, o.long("undoUntil"), o.long("sendAt"))
    }

    /** `{ draftId }` of `POST outbox/<id>/cancel`. */
    fun cancelled(o: JsonObject): Long? = o.id("draftId")

    fun outbox(o: JsonObject): List<MOutboxItem>? = o.arr("items")?.objects()?.mapNotNull { i ->
        val id = i.id("id") ?: return@mapNotNull null
        MOutboxItem(
            id = id,
            draftId = i.id("draftId"),
            accountId = i.id("accountId"),
            subject = i.str("subject") ?: "",
            sendAt = i.long("sendAt"),
            state = i.str("state") ?: "waiting",
            error = i.str("error") ?: i.obj("error")?.str("code"),
        )
    }

    fun contacts(o: JsonObject): List<MContact>? = o.arr("items")?.objects()?.mapNotNull { c ->
        val email = c.str("email")?.trim()?.takeIf { it.contains('@') } ?: return@mapNotNull null
        MContact(c.str("name")?.trim()?.takeIf { it.isNotEmpty() }, email, c.str("source") ?: "recent")
    }

    // ─── Drive ──────────────────────────────────────────────────────

    fun suggest(o: JsonObject): MSuggest = MSuggest(suggestion(o.obj("suggestion")), o.bool("duplicate") ?: false)

    /** `{ savedTo }`. */
    fun savedAnswer(o: JsonObject): MSavedTo? = savedTo(o.obj("savedTo"))

    fun saveAll(o: JsonObject): MSaveAll = MSaveAll(
        saved = o.arr("saved")?.objects()?.mapNotNull { s -> s.id("attachmentId")?.let { a -> savedTo(s.obj("savedTo"))?.let { a to it } } }.orEmpty(),
        failed = o.arr("failed")?.objects()?.mapNotNull { f -> f.id("attachmentId")?.let { it to (f.str("code") ?: "failed") } }.orEmpty(),
    )

    fun filing(o: JsonObject): List<MFilingItem>? = o.arr("items")?.objects()?.mapNotNull { i ->
        val id = i.id("id") ?: return@mapNotNull null
        MFilingItem(
            id = id,
            messageId = i.id("messageId") ?: return@mapNotNull null,
            attachmentId = i.id("attachmentId") ?: return@mapNotNull null,
            name = i.str("name") ?: "attachment",
            from = i.obj("from")?.let(::address) ?: i.str("from")?.let(MailLogic::parseAddress),
            date = i.long("date"),
            suggestion = suggestion(i.obj("suggestion")),
        )
    }

    // ─── live ───────────────────────────────────────────────────────

    /** Realtime event `mail` (or the plugin's data under `mail`): `{ kind: 'changed', accountId, folderId }`. */
    fun changed(data: JsonElement?): MChanged? {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val root = o.obj("mail") ?: o
        if (root.str("kind") != "changed") return null
        return MChanged(root.id("accountId"), root.id("folderId"), root.id("messageId"), root.id("threadId"), root.id("attachmentId"))
    }

    /**
     * `409 busy { reason, retryAfter }` (the error object of [MResult.Failed]): when to ask again, in seconds (1…60,
     * 5 without a value); null when the refusal is no `busy`.
     */
    fun busyRetryAfter(code: String?, error: JsonObject?): Int? {
        if (code != "busy") return null
        val err = error?.obj("error") ?: error
        return (err?.int("retryAfter") ?: 5).coerceIn(1, 60)
    }

    /** `busy` with `reason: 'loading'`: an attachment (or embedded picture) still on its way from the provider. */
    fun busyLoading(code: String?, error: JsonObject?): Boolean {
        if (code != "busy") return false
        val err = error?.obj("error") ?: error
        return err?.str("reason") == "loading"
    }

    // ─── request bodies ─────────────────────────────────────────────

    fun idsBody(ids: Collection<Long>): JsonObject = JsonObject().apply {
        add("ids", JsonArray().apply { ids.forEach { add(it) } })
    }

    fun flagsBody(ids: Collection<Long>, unread: Boolean?, flagged: Boolean?): JsonObject = idsBody(ids).apply {
        unread?.let { addProperty("unread", it) }
        flagged?.let { addProperty("flagged", it) }
    }

    fun moveBody(ids: Collection<Long>, folderId: Long): JsonObject = idsBody(ids).apply { addProperty("folderId", folderId) }

    fun tabBody(ids: Collection<Long>, tab: String): JsonObject = idsBody(ids).apply { addProperty("tab", tab) }

    fun settingsBody(threads: Boolean? = null, swipeLeft: String? = null, swipeRight: String? = null): JsonObject = JsonObject().apply {
        threads?.let { addProperty("threads", it) }
        swipeLeft?.takeIf { it in MSettings.SWIPES }?.let { addProperty("swipeLeft", it) }
        swipeRight?.takeIf { it in MSettings.SWIPES }?.let { addProperty("swipeRight", it) }
    }

    private fun serverBody(s: MServer): JsonObject = JsonObject().apply {
        addProperty("host", s.host)
        addProperty("port", s.port)
        addProperty("security", s.security)
    }

    fun accountBody(
        provider: String,
        email: String,
        password: String,
        username: String?,
        name: String?,
        color: String?,
        kind: String?,
        imap: MServer?,
        smtp: MServer?,
    ): JsonObject = JsonObject().apply {
        addProperty("provider", provider)
        addProperty("email", email.trim())
        addProperty("password", password)
        username?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("username", it) }
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("name", it) }
        color?.takeIf { COLOR.matches(it) }?.let { addProperty("color", it) }
        kind?.let { addProperty("kind", it) }
        imap?.let { add("imap", serverBody(it)) }
        smtp?.let { add("smtp", serverBody(it)) }
    }

    fun microsoftBody(code: String, verifier: String, redirectUri: String, name: String?, color: String?, kind: String?): JsonObject = JsonObject().apply {
        addProperty("code", code)
        addProperty("codeVerifier", verifier)
        addProperty("redirectUri", redirectUri)
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("name", it) }
        color?.takeIf { COLOR.matches(it) }?.let { addProperty("color", it) }
        kind?.let { addProperty("kind", it) }
    }

    /** `PATCH accounts/<id>`: only what is given. */
    fun accountPatch(
        name: String? = null,
        color: String? = null,
        kind: String? = null,
        push: String? = null,
        ai: Boolean? = null,
        attachments: String? = null,
        signature: String? = null,
        password: String? = null,
    ): JsonObject = JsonObject().apply {
        name?.let { addProperty("name", it.trim()) }
        color?.takeIf { COLOR.matches(it) }?.let { addProperty("color", it) }
        kind?.let { addProperty("kind", it) }
        push?.takeIf { it in MAccount.PUSH }?.let { addProperty("push", it) }
        ai?.let { addProperty("ai", it) }
        attachments?.takeIf { it in MAccount.ATTACHMENTS }?.let { addProperty("attachments", it) }
        signature?.let { addProperty("signature", it) }
        password?.let { addProperty("password", it) }
    }

    private fun addressArray(list: List<MAddress>): JsonArray = JsonArray().apply {
        list.forEach { a ->
            add(JsonObject().apply {
                a.name?.let { addProperty("name", it) }
                addProperty("email", a.email)
            })
        }
    }

    fun att(a: MAtt): JsonObject = JsonObject().apply {
        when (a) {
            is MAtt.Kept -> addProperty("id", a.id)
            is MAtt.Upload -> {
                addProperty("mediaId", a.mediaId)
                addProperty("name", a.name)
            }
            is MAtt.Forwarded -> {
                addProperty("messageId", a.messageId)
                addProperty("attachmentId", a.attachmentId)
            }
            is MAtt.Drive -> {
                addProperty("driveNodeId", a.nodeId)
                addProperty("as", if (a.asLink) "link" else "copy")
                if (a.asLink) {
                    a.expiresDays?.coerceIn(1, 90)?.let { addProperty("expiresDays", it) }
                    a.password?.takeIf { it.isNotEmpty() }?.let { addProperty("password", it) }
                }
            }
        }
    }

    /**
     * `POST drafts` / `PUT drafts/<id>`. With [mode] (reply, replyAll, forward) and [replyTo] the server fills
     * recipients, subject and quote in when they are left out: null fields are not sent.
     */
    fun draftBody(
        accountId: Long?,
        to: List<MAddress>?,
        cc: List<MAddress>?,
        bcc: List<MAddress>?,
        subject: String?,
        html: String?,
        replyTo: Long? = null,
        mode: String? = null,
        attachments: List<MAtt> = emptyList(),
    ): JsonObject = JsonObject().apply {
        accountId?.takeIf { it > 0 }?.let { addProperty("accountId", it) }
        to?.let { add("to", addressArray(it)) }
        cc?.let { add("cc", addressArray(it)) }
        bcc?.let { add("bcc", addressArray(it)) }
        subject?.let { addProperty("subject", it) }
        html?.let { addProperty("html", it) }
        replyTo?.takeIf { it > 0 }?.let { addProperty("replyTo", it) }
        mode?.takeIf { it in MODES }?.let { addProperty("mode", it) }
        add("attachments", JsonArray().apply { attachments.forEach { add(att(it)) } })
    }

    val MODES = setOf("reply", "replyAll", "forward")

    fun sendBody(sendAt: Long?): JsonObject = JsonObject().apply { sendAt?.let { addProperty("sendAt", it) } }

    fun imagesBody(sender: Boolean): JsonObject = JsonObject().apply { addProperty("allow", if (sender) "sender" else "once") }

    fun todoBody(): JsonObject = JsonObject().apply { addProperty("action", "family_calendar") }

    fun driveSaveBody(folderId: Long?, name: String?, remember: Boolean?): JsonObject = JsonObject().apply {
        folderId?.let { addProperty("folderId", it) }
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("name", it) }
        remember?.let { addProperty("remember", it) }
    }

    fun discoverBody(email: String): JsonObject = JsonObject().apply { addProperty("email", email.trim()) }
}
