package com.gatecontrol.android.mail

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The Mail tab's rules (pure, unit-tested): which list a box shows, the swipe gestures, the undo window after
 * sending, the time column, addresses, the HTML shown in the WebView (embedded pictures, the requests it may make)
 * and the body of a draft.
 */
object MailLogic {

    /** "Alle Posteingänge". */
    const val BOX_ALL = "all"

    /** The undo window the server keeps after `send` (contract: 10 s). */
    const val UNDO_MS = 10_000L

    /** Offline: at least this much of the inbox stays on the device. */
    const val OFFLINE_KEEP_MS = 30L * 24 * 60 * 60 * 1000

    // ─── boxes and lists ────────────────────────────────────────────

    /** A box: all inboxes ([accountId] null) or one account, optionally one of its folders. */
    data class Box(val accountId: Long? = null, val folderId: Long? = null) {
        val all: Boolean get() = accountId == null
        /** An inbox (all, or an account without a folder, or its inbox folder): has Relevant / Sonstige. */
        fun isInbox(folders: List<MFolder>): Boolean =
            folderId == null || folders.firstOrNull { it.id == folderId }?.role == MFolder.ROLE_INBOX

        /** `box` of `GET messages`. */
        val query: String get() = accountId?.toString() ?: BOX_ALL

        /** The key of its offline copy. */
        fun key(tab: String): String = "box-${query}-${folderId ?: 0}-$tab"
    }

    /** The query of `GET messages` for a box. */
    fun listQuery(
        box: Box,
        tab: String,
        inbox: Boolean,
        before: String? = null,
        unread: Boolean = false,
        flagged: Boolean = false,
        attachments: Boolean = false,
        limit: Int = 50,
    ): Map<String, String> = buildMap {
        put("box", box.query)
        if (box.accountId != null) box.folderId?.let { put("folder", it.toString()) }
        if (inbox) put("tab", tab)
        if (unread) put("unread", "1")
        if (flagged) put("flagged", "1")
        if (attachments) put("attachments", "1")
        before?.let { put("before", it) }
        put("limit", limit.coerceIn(1, 100).toString())
    }

    /** Pages one after the other: the newer page's items replace the same ids of an older one. */
    fun merge(old: List<MSummary>, more: List<MSummary>): List<MSummary> {
        val byId = more.associateBy { it.id }
        val oldIds = old.mapTo(HashSet()) { it.id }
        return old.map { byId[it.id] ?: it } + more.filter { it.id !in oldIds }.distinctBy { it.id }
    }

    /** The list after a change to some mails: [changed] replace theirs, [removed] go. */
    fun apply(list: List<MSummary>, changed: List<MSummary>, removed: Set<Long> = emptySet()): List<MSummary> {
        val byId = changed.associateBy { it.id }
        return list.filter { it.id !in removed }.map { byId[it.id] ?: it }
    }

    /** Whether a mail still belongs to the list it is in after [action] (archive, delete, junk, move take it away). */
    fun leavesList(action: String): Boolean = action in setOf(SWIPE_ARCHIVE, SWIPE_DELETE, "junk", "move", "tab")

    // ─── the menu: an account's folders ─────────────────────────────

    /**
     * Whether the menu shows [accountId]'s folders: as the person folded it this session ([folds]), else open for the
     * account of the [box] shown.
     */
    fun foldOpen(folds: Map<Long, Boolean>, accountId: Long, box: Box): Boolean = folds[accountId] ?: (box.accountId == accountId)

    /** [folds] after a tap on the account (its folders open or close; the menu stays). */
    fun toggleFold(folds: Map<Long, Boolean>, accountId: Long, box: Box): Map<Long, Boolean> =
        folds + (accountId to !foldOpen(folds, accountId, box))

    // ─── the menu: an account's folders as a tree ───────────────────

    /** The menu's order of the folders with a role; the own ones ([MFolder.role] null) stand between archive and junk. */
    val FOLDER_ORDER = listOf(MFolder.ROLE_INBOX, MFolder.ROLE_SENT, MFolder.ROLE_DRAFTS, MFolder.ROLE_ARCHIVE, null, MFolder.ROLE_JUNK, MFolder.ROLE_TRASH)

    /** From this depth on the rows are not indented any further. */
    const val FOLDER_MAX_INDENT = 4

    /** A folder in the tree: the folder it hangs below ([parent], null on top), its children and the unread mail below it. */
    data class FolderNode(val folder: MFolder, val depth: Int, val parent: Long?, val kids: List<Long>, val unreadBelow: Int)

    /** An account's folders as a tree: [nodes] in the menu's order (each parent before its children). */
    class FolderTree(val nodes: List<FolderNode>) {
        val byId: Map<Long, FolderNode> = nodes.associateBy { it.folder.id }
    }

    /** A row of the menu: [indent] the depth up to [FOLDER_MAX_INDENT]; [unreadBelow] only for a closed parent. */
    data class FolderRow(val folder: MFolder, val depth: Int, val indent: Int, val hasKids: Boolean, val open: Boolean, val unreadBelow: Int)

    private fun folderRank(f: MFolder): Int = FOLDER_ORDER.indexOf(f.role).let { if (it < 0) FOLDER_ORDER.indexOf(null) else it }

    private val folderSort: Comparator<MFolder> = compareBy<MFolder>({ folderRank(it) }, { it.name.lowercase() }, { it.id })

    /**
     * The tree of an account's folders (`parentId`): the folders with a role always on top in [FOLDER_ORDER], the own
     * ones by name; children below their parent, by name. A parent the list does not hold (not synced) leaves its
     * children on top, so does a loop of parents.
     */
    fun folderTree(folders: List<MFolder>): FolderTree {
        val ids = folders.associateBy { it.id }
        fun parentOf(f: MFolder): Long? = if (f.role != null) null else f.parentId?.takeIf { it != f.id && it in ids }
        val kids = folders.groupBy { parentOf(it) }
        val nodes = ArrayList<FolderNode>(folders.size)
        val seen = HashSet<Long>()
        // → the unread of the folder and all below it
        fun walk(f: MFolder, depth: Int, parent: Long?): Int {
            val at = nodes.size
            nodes.add(FolderNode(f, depth, parent, emptyList(), 0))
            val mine = ArrayList<Long>()
            var below = 0
            for (c in kids[f.id].orEmpty().sortedWith(folderSort)) {
                if (!seen.add(c.id)) continue
                mine.add(c.id)
                below += walk(c, depth + 1, f.id)
            }
            nodes[at] = FolderNode(f, depth, parent, mine, below)
            return f.unread + below
        }
        for (f in kids[null].orEmpty().sortedWith(folderSort)) if (seen.add(f.id)) walk(f, 0, null)
        for (f in folders.sortedWith(folderSort)) if (seen.add(f.id)) walk(f, 0, null)
        return FolderTree(nodes)
    }

    /** The folders above [folderId] in the tree, its parent first. */
    fun folderAncestors(tree: FolderTree, folderId: Long?): List<Long> {
        val out = ArrayList<Long>()
        var n = folderId?.let { tree.byId[it] }
        while (n?.parent != null && n.parent !in out) {
            out.add(n.parent!!)
            n = tree.byId[n.parent!!]
        }
        return out
    }

    /**
     * Whether a folder shows its children: as the person folded it ([folderFolds], kept on the device), else closed —
     * open when the folder shown ([current]) lies below it.
     */
    fun folderOpen(tree: FolderTree, folderFolds: Map<Long, Boolean>, folderId: Long, current: Long?): Boolean =
        folderFolds[folderId] ?: (folderId in folderAncestors(tree, current))

    /** [folderFolds] after a tap on a folder's arrow (it opens or closes; nothing else changes). */
    fun toggleFolder(tree: FolderTree, folderFolds: Map<Long, Boolean>, folderId: Long, current: Long?): Map<Long, Boolean> =
        folderFolds + (folderId to !folderOpen(tree, folderFolds, folderId, current))

    /** [folderFolds] with every folder above [folderId] open (a folder picked or pointed at is never hidden). */
    fun revealFolder(tree: FolderTree, folderFolds: Map<Long, Boolean>, folderId: Long?): Map<Long, Boolean> {
        val up = folderAncestors(tree, folderId)
        return if (up.all { folderFolds[it] == true }) folderFolds else folderFolds + up.associateWith { true }
    }

    /** The rows the menu shows: the children of a closed folder are left out. */
    fun folderRows(tree: FolderTree, folderFolds: Map<Long, Boolean>, current: Long?): List<FolderRow> {
        val out = ArrayList<FolderRow>(tree.nodes.size)
        var hiddenBelow = -1
        for (n in tree.nodes) {
            if (hiddenBelow >= 0) {
                if (n.depth > hiddenBelow) continue
                hiddenBelow = -1
            }
            val has = n.kids.isNotEmpty()
            val open = has && folderOpen(tree, folderFolds, n.folder.id, current)
            out.add(FolderRow(n.folder, n.depth, minOf(n.depth, FOLDER_MAX_INDENT), has, open, if (has && !open) n.unreadBelow else 0))
            if (has && !open) hiddenBelow = n.depth
        }
        return out
    }

    // ─── a conversation ─────────────────────────────────────────────

    /**
     * A conversation (`GET threads/<id>`) around the open mail [currentId]: the mails before it and those after it,
     * each oldest first (the newest at the bottom, as in Outlook). Without the open mail in it, all count as before.
     */
    fun conversation(items: List<MMessage>, currentId: Long): Pair<List<MMessage>, List<MMessage>> {
        val sorted = items.distinctBy { it.id }.sortedWith(compareBy({ it.summary.date }, { it.id }))
        val at = sorted.indexOfFirst { it.id == currentId }
        if (at < 0) return sorted to emptyList()
        return sorted.subList(0, at) to sorted.subList(at + 1, sorted.size)
    }

    /** The senders of a conversation for its line ("Anna, Tim"), each once, at most [max]. */
    fun participants(items: List<MSummary>, max: Int = 3): List<String> =
        items.map { it.from }.distinctBy { it.email.lowercase() }.map { it.label }.filter { it.isNotBlank() }.take(max)

    // ─── a sender ───────────────────────────────────────────────────

    /** `GET search` for the mails of [email] in the account [accountId] (all accounts without one). */
    fun senderQuery(email: String, accountId: Long?, cursor: String? = null): Map<String, String> = buildMap {
        put("from", email.trim().lowercase())
        put("box", accountId?.toString() ?: BOX_ALL)
        cursor?.let { put("cursor", it) }
    }

    /** The server's `from` matches a part of the address: only the mails of exactly [email] stay. */
    fun fromSender(items: List<MSummary>, email: String): List<MSummary> = items.filter { it.from.email.trim().equals(email.trim(), ignoreCase = true) }

    /** The name of [email] in the person's contacts (`contacts?q=`, a `person` hit), null when there is none. */
    fun contactOf(contacts: List<MContact>, email: String): MContact? =
        contacts.firstOrNull { it.source == MContact.SOURCE_PERSON && it.email.trim().equals(email.trim(), ignoreCase = true) && !it.name.isNullOrBlank() }

    // ─── swipes ─────────────────────────────────────────────────────

    const val SWIPE_ARCHIVE = MSettings.SWIPE_ARCHIVE
    const val SWIPE_DELETE = MSettings.SWIPE_DELETE
    const val SWIPE_READ = MSettings.SWIPE_READ

    /** What a swipe does (null: nothing, the row does not move). `read` flips read / unread. */
    fun swipe(setting: String?): String? = setting?.takeIf { it == SWIPE_ARCHIVE || it == SWIPE_DELETE || it == SWIPE_READ }

    // ─── undo after sending ─────────────────────────────────────────

    /**
     * How long "Rückgängig" stays after `send` answered at [now]: the server's [undoUntil] when it lies within the
     * contract's 10 s, else (clocks apart) 10 s less what the call took ([callMs]). Never negative.
     */
    fun undoWindow(undoUntil: Long?, now: Long, callMs: Long = 0L): Long {
        val left = undoUntil?.let { it - now }
        return when {
            left != null && left in 1..UNDO_MS -> left
            else -> (UNDO_MS - callMs.coerceAtLeast(0)).coerceIn(0, UNDO_MS)
        }
    }

    /** Whether the send was planned for later (not just the undo time). */
    fun planned(sendAt: Long?, now: Long): Boolean = sendAt != null && sendAt - now > UNDO_MS + 5_000

    /** Suggested times for "Senden planen": tomorrow 08:00, Monday 08:00 (when tomorrow is not Monday). */
    fun planChoices(now: Long, zone: ZoneId): List<Long> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val tomorrow = today.plusDays(1)
        val out = mutableListOf(tomorrow.atTime(8, 0).atZone(zone).toInstant().toEpochMilli())
        var monday = today.plusDays(1)
        while (monday.dayOfWeek != java.time.DayOfWeek.MONDAY) monday = monday.plusDays(1)
        if (monday != tomorrow) out += monday.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        return out
    }

    // ─── the time column ────────────────────────────────────────────

    enum class When { TIME, YESTERDAY, WEEKDAY, DATE, DATE_YEAR }

    /** How a mail's [date] is shown on [now]: the time today, "Gestern", the weekday this week, else the date. */
    fun whenOf(date: Long, now: Long, zone: ZoneId): When {
        val d = Instant.ofEpochMilli(date).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(d, today)
        return when {
            days <= 0L -> When.TIME
            days == 1L -> When.YESTERDAY
            days < 7L -> When.WEEKDAY
            d.year == today.year -> When.DATE
            else -> When.DATE_YEAR
        }
    }

    // ─── addresses, names ───────────────────────────────────────────

    private val ANGLE = Regex("^\\s*\"?([^\"<]*?)\"?\\s*<\\s*([^<>\\s]+@[^<>\\s]+)\\s*>\\s*$")
    private val EMAIL = Regex("^[^@\\s<>,;\"]+@[^@\\s<>,;\"]+\\.[^@\\s<>,;\"]+$")

    fun isEmail(s: String): Boolean = EMAIL.matches(s.trim())

    /** `Name <a@b.de>` or `a@b.de` → the address; null when there is none. */
    fun parseAddress(raw: String?): MAddress? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        ANGLE.matchEntire(s)?.let { m ->
            val email = m.groupValues[2].trim()
            if (!isEmail(email)) return null
            return MAddress(m.groupValues[1].trim().takeIf { it.isNotEmpty() }, email)
        }
        return if (isEmail(s)) MAddress(null, s) else null
    }

    /** What was typed into a recipient field: addresses separated by `,` `;` or spaces; the rest is [Parsed.rest]. */
    data class Parsed(val addresses: List<MAddress>, val rest: String)

    fun parseRecipients(input: String): Parsed {
        val parts = input.split(',', ';', '\n')
        val out = ArrayList<MAddress>()
        val rest = ArrayList<String>()
        for (p in parts) {
            val t = p.trim()
            if (t.isEmpty()) continue
            val one = parseAddress(t)
            if (one != null) {
                out += one
                continue
            }
            // "a@b.de c@d.de" without commas
            val words = t.split(Regex("\\s+"))
            if (words.size > 1 && words.all { isEmail(it) }) out += words.map { MAddress(null, it) } else rest += t
        }
        return Parsed(out.distinctBy { it.email.lowercase() }, rest.joinToString(", "))
    }

    /** Two letters for the round avatar: first letters of the first two words, else of the address. */
    fun initials(name: String?, email: String): String {
        val words = name?.trim()?.split(Regex("\\s+"))?.filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }.orEmpty()
        val letters = when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}"
            words.size == 1 -> words[0].take(1)
            else -> email.trim().take(1)
        }
        return letters.uppercase().ifEmpty { "?" }
    }

    /** `#RRGGBB` → ARGB, null when it is not one. */
    fun color(hex: String?): Long? {
        val h = hex?.takeIf { it.length == 7 && it.startsWith('#') } ?: return null
        return h.substring(1).toLongOrNull(16)?.let { 0xFF000000L or it }
    }

    /** Colors offered for a new account (as the mockups show them). */
    val COLORS = listOf("#F5B544", "#79B8FF", "#FF8080", "#B79CFF", "#46E0A0", "#FF9F6E")

    /** The next color for a new account: the first not in use, else round robin. */
    fun nextColor(used: List<String?>): String =
        COLORS.firstOrNull { c -> used.none { it.equals(c, ignoreCase = true) } } ?: COLORS[used.size % COLORS.size]

    // ─── accounts: the provider's mark ──────────────────────────────

    /** The round mark of an account without a picture (own simple signs, no provider logos). */
    enum class Mark { OUTLOOK, M365, GMAIL, GMX, WEBDE, TONLINE, OTHER }

    fun mark(provider: String?): Mark = when (provider) {
        MAccount.PROVIDER_OUTLOOK -> Mark.OUTLOOK
        MAccount.PROVIDER_M365 -> Mark.M365
        MAccount.PROVIDER_GMAIL -> Mark.GMAIL
        MAccount.PROVIDER_GMX -> Mark.GMX
        MAccount.PROVIDER_WEBDE -> Mark.WEBDE
        MAccount.PROVIDER_TONLINE -> Mark.TONLINE
        else -> Mark.OTHER
    }

    // ─── loading later (Mail 1.1.2) ─────────────────────────────────

    /** How often an attachment or embedded picture still loading (`busy`) is asked for again by itself. */
    const val BUSY_RETRIES = 3

    /** `busy`'s `retryAfter` (seconds) as a wait; never below a second, never above a minute. */
    fun retryDelayMs(retryAfter: Int?): Long = (retryAfter ?: 5).coerceIn(1, 60) * 1000L

    /** Whether a live event `mail` concerns the open mail [messageId] (or its conversation [threadId]). */
    fun concerns(changed: MChanged, messageId: Long, threadId: Long?): Boolean =
        changed.messageId == messageId || (threadId != null && changed.threadId == threadId)

    /** The attachment of an embedded picture's path `cid/<mail>/<attachment>`; null for anything else. */
    fun cidAttachment(path: String): Long? {
        val parts = path.split('/')
        return if (parts.size == 3 && parts[0] == "cid") parts[2].toLongOrNull()?.takeIf { it > 0 } else null
    }

    /** Quota in percent (0…100), null without a quota. */
    fun quotaPercent(used: Long, quota: Long): Int? = if (quota <= 0) null else ((used.coerceAtLeast(0) * 100.0) / quota).toInt().coerceIn(0, 100)

    /** A Drive suggestion's sureness in percent. */
    fun percent(confidence: Double): Int = (confidence.coerceIn(0.0, 1.0) * 100).toInt()

    /** Kept automatically by Drive (contract: rule, learned or ≥ 0.9). */
    fun autoFiled(s: MSuggestion): Boolean = s.reason == "rule" || s.reason == "learned" || s.confidence >= 0.9

    // ─── dates of the AI's to-dos ───────────────────────────────────

    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    fun isIsoDate(s: String): Boolean = ISO_DATE.matches(s) && runCatching { LocalDate.parse(s) }.isSuccess

    fun isTime(s: String): Boolean = TIME.matches(s)

    /** Start of a to-do in the device's calendar (ms), null without a date. All day when there is no time. */
    fun todoStart(todo: MTodo, zone: ZoneId): Pair<Long, Boolean>? {
        val date = todo.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val time = todo.time?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        return if (time != null) {
            date.atTime(time).atZone(zone).toInstant().toEpochMilli() to false
        } else {
            // all-day events are given in UTC midnight
            date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() to true
        }
    }

    /** A to-do the calendar can take (a deadline or an event with a date). */
    fun calendarTodo(todo: MTodo): Boolean = (todo.kind == MTodo.DEADLINE || todo.kind == MTodo.EVENT) && todo.date != null

    // ─── HTML in the WebView ────────────────────────────────────────

    /** The address the mail's HTML is loaded under; its requests are answered by the app (never the network). */
    const val BASE_HOST = "mail.gatecontrol.invalid"
    const val BASE_URL = "https://$BASE_HOST/"

    private val IMG_TOKEN = Regex("^[A-Za-z0-9_.~-]{1,300}$")
    private val CID_SRC = Regex("(?i)(\\b(?:src|background)\\s*=\\s*)([\"']?)cid:([^\"'\\s>]+)\\2")

    /**
     * `cid:` pictures → `cid/<messageId>/<attachmentId>` (the plugin's route, contract C2); a `cid:` without an
     * attachment stays (it loads nothing).
     */
    fun rewriteCid(html: String, messageId: Long, attachments: List<MAttachment>): String {
        val byCid = attachments.filter { it.cid != null }.associateBy { it.cid!!.lowercase() }
        return CID_SRC.replace(html) { m ->
            val cid = java.net.URLDecoder.decode(m.groupValues[3].replace("+", "%2B"), "UTF-8").removePrefix("<").removeSuffix(">")
            val a = byCid[cid.lowercase()] ?: return@replace m.value
            val q = m.groupValues[2].ifEmpty { "\"" }
            "${m.groupValues[1]}${q}cid/$messageId/${a.id}$q"
        }
    }

    /**
     * The plugin route a WebView request may load (`img/<token>`, `cid/<message>/<attachment>`), null for anything
     * else: such a request is answered empty, the mail never reaches the network by itself.
     */
    fun interceptPath(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != BASE_HOST || uri.rawQuery != null) return null
        val parts = (uri.rawPath ?: return null).trimStart('/').split('/')
        return when {
            parts.size == 2 && parts[0] == "img" && IMG_TOKEN.matches(parts[1]) -> "img/${parts[1]}"
            parts.size == 3 && parts[0] == "cid" && parts[1].toLongOrNull()?.let { it > 0 } == true &&
                parts[2].toLongOrNull()?.let { it > 0 } == true -> "cid/${parts[1]}/${parts[2]}"
            else -> null
        }
    }

    /** Links a tap may open outside the app (the rest is ignored). */
    fun externalLink(url: String): Boolean {
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        return scheme == "https" || scheme == "http" || scheme == "mailto" || scheme == "tel"
    }

    /** `mailto:a@b.de?subject=…` → the address (null for anything else). */
    fun mailtoAddress(url: String): MAddress? {
        if (!url.startsWith("mailto:", ignoreCase = true)) return null
        val addr = java.net.URLDecoder.decode(url.substring(7).substringBefore('?'), "UTF-8")
        return parseRecipients(addr).addresses.firstOrNull()
    }

    /** The page's margin (CSS px) around the mail. */
    const val PAGE_PADDING = 12

    /** Below this a mail is not made any smaller to fit (pinching still zooms in). */
    const val MIN_ZOOM = 0.25

    /** How often a page is laid out again to fit (each time only smaller, so it always ends). */
    const val FIT_ROUNDS = 3

    /**
     * The page the WebView shows: the mail's (already cleaned) HTML with a strict content policy (no scripts, no
     * frames, pictures only from the app's own address) and a width that fits the phone: pictures, tables, long
     * links and preformatted text stay inside the screen. What is still wider (a newsletter's table of a fixed 600 px)
     * is made smaller as a whole with [zoom] (CSS `zoom` on the mail, as mail apps do), measured from outside by
     * [fitZoom] — the page itself never runs a script. Pinching still zooms in.
     *
     * [dark]: the app's dark page around a mail whose colours [darkMail] changed already (as the Outlook app shows
     * mails while it is dark); else the light page the mail was made for.
     */
    fun page(html: String, dark: Boolean = false, zoom: Double = 1.0): String {
        val csp = "default-src 'none'; img-src $BASE_URL data:; style-src 'unsafe-inline'; font-src data:; " +
            "script-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"
        val colors = if (dark) MailDark.pageCss() else ":root{color-scheme:light}html,body{background:#FFFFFF;color:#1A1D22}"
        val z = zoom.coerceIn(MIN_ZOOM, 1.0)
        val fit = if (z < 1.0) "#gc-fit{zoom:${String.format(java.util.Locale.ROOT, "%.3f", z)}}" else ""
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$csp\">" +
            "<meta name=\"color-scheme\" content=\"${if (dark) "dark" else "light"}\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>html,body{margin:0;padding:0}${colors}body{padding:${PAGE_PADDING}px;font-family:sans-serif;font-size:15px;line-height:1.5;" +
            "word-wrap:break-word;overflow-wrap:anywhere}" +
            "img{max-width:100%;height:auto}table{max-width:100%}td,th{overflow-wrap:anywhere}" +
            "a{overflow-wrap:anywhere;word-break:break-word}pre{white-space:pre-wrap;overflow-wrap:anywhere}$fit</style></head><body>" +
            "<div id=\"gc-fit\">" + html + "</div></body></html>"
    }

    /**
     * A mail's (cleaned) HTML in the dark ([MailDark]): light backgrounds dark, dark text light, the hue kept, text
     * readable (at least 4.5 : 1), pictures never touched. The original stays what the server sent (answering and
     * forwarding use the server's copy).
     */
    fun darkMail(html: String): String = MailDark.html(html)

    /**
     * Whether a mail is shown dark: the app is dark, "Mails dunkel darstellen" is on and the mail was not switched to
     * light ("Hell anzeigen").
     */
    fun showDark(appDark: Boolean, setting: Boolean, shownLight: Boolean): Boolean = appDark && setting && !shownLight

    /**
     * The [page]'s zoom so the mail fits without scrolling sideways, from what the WebView measured: [viewCss] its
     * width and [contentCss] the width of what it shows (both in CSS px, the content at the [current] zoom). Null
     * when it fits already (1 px of rounding allowed) or would not get smaller; never below [MIN_ZOOM].
     */
    fun fitZoom(viewCss: Double, contentCss: Double, current: Double = 1.0): Double? {
        if (viewCss <= 2 * PAGE_PADDING || contentCss <= viewCss + 1.0 || current <= MIN_ZOOM) return null
        // the mail at zoom 1 (the margin on the left is not zoomed; one on the right may count or not)
        val mail = (contentCss - PAGE_PADDING) / current
        val zoom = ((viewCss - 2 * PAGE_PADDING) / mail).coerceIn(MIN_ZOOM, 1.0)
        val rounded = kotlin.math.floor(zoom * 1000) / 1000
        return rounded.takeIf { it < current - 0.001 }
    }

    // ─── the body of a draft ────────────────────────────────────────

    /** Marks the part of a draft's HTML the person typed in this app (the rest is the quote below it). */
    private const val TEXT_OPEN = "<div data-gc-text=\"1\">"
    private const val TEXT_CLOSE = "</div><!--gc-text-->"

    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** Plain text → HTML (lines kept). */
    fun textToHtml(text: String): String = escape(text.replace("\r\n", "\n")).replace("\n", "<br>")

    /** The draft's HTML: the typed [text] and, below it, the [quote] (the server's HTML, unchanged). */
    fun composeHtml(text: String, quote: String): String = TEXT_OPEN + textToHtml(text) + TEXT_CLOSE + quote

    /** A draft's HTML taken apart again: what was typed here and the quote. Another app's draft: all of it as text. */
    fun splitDraft(html: String): Pair<String, String> {
        val start = html.indexOf(TEXT_OPEN)
        val end = html.indexOf(TEXT_CLOSE)
        if (start == 0 && end > start) {
            val inner = html.substring(TEXT_OPEN.length, end)
            return htmlToText(inner) to html.substring(end + TEXT_CLOSE.length)
        }
        if (start < 0 && html.isNotBlank() && looksQuoted(html)) return "" to html
        return htmlToText(html) to ""
    }

    /** The server's prefilled answer or forward starts with its quote (contract: Zitat). */
    private fun looksQuoted(html: String): Boolean {
        val h = html.trimStart().lowercase()
        return h.startsWith("<blockquote") || h.startsWith("<br><blockquote") || h.startsWith("<div class=\"gc-quote") ||
            h.startsWith("<p><br></p><blockquote") || h.startsWith("<br><div") || h.startsWith("<div><br></div>")
    }

    private val TAG = Regex("<[^>]*>")
    private val BR = Regex("(?i)<br\\s*/?>")
    private val BLOCK_END = Regex("(?i)</(p|div|li|tr|h[1-6])>")

    /** Rough HTML → text (lines kept, tags dropped, the common entities decoded). */
    fun htmlToText(html: String): String = html
        .replace(BR, "\n")
        .replace(BLOCK_END, "\n")
        .replace(TAG, "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .trimEnd('\n')

    /** A new mail's text with the account's signature below (`-- ` line as usual). */
    fun withSignature(text: String, signature: String?): String {
        val sig = signature?.trim()?.takeIf { it.isNotEmpty() } ?: return text
        if (text.contains(sig)) return text
        return text + "\n\n-- \n" + sig
    }

    /** A suggestion of the AI put above what the server prefilled. */
    fun withReply(reply: String, text: String): String = if (text.isBlank()) reply else reply + "\n\n" + text

    // ─── attachments ────────────────────────────────────────────────

    /** Short type label of the file icon (`PDF`, `DOC`, `XLS`, `IMG` …). */
    fun typeLabel(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime == "application/pdf" || ext == "pdf" -> "PDF"
            ext in setOf("doc", "docx", "odt", "rtf") -> "DOC"
            ext in setOf("xls", "xlsx", "ods", "csv") -> "XLS"
            ext in setOf("ppt", "pptx", "odp") -> "PPT"
            mime.startsWith("image/") -> "IMG"
            ext in setOf("zip", "7z", "rar", "gz", "tar") -> "ZIP"
            ext == "ics" || mime == "text/calendar" -> "ICS"
            ext.length in 1..4 -> ext.uppercase()
            else -> "FILE"
        }
    }

    /** Above this a device file cannot go as a copy (the server refuses it, 413); a Drive file goes as a link. */
    const val ATTACH_MAX_BYTES = 25L * 1024 * 1024

    /** Whether a Drive file of [size] is offered as a link first ("zu groß zum Anhängen"). */
    fun linkFirst(size: Long): Boolean = size > 20L * 1024 * 1024

    /** A file name of a picked file, cleaned for the server. */
    fun fileName(raw: String?): String {
        val n = raw?.substringAfterLast('/')?.replace(Regex("[\\u0000-\\u001f\\\\:*?\"<>|]"), "_")?.trim()?.take(180)
        return n?.takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "attachment"
    }
}
