package com.gatecontrol.android.notizen

import java.text.Normalizer
import java.util.Locale

/**
 * The text of a note (plugin `ui/notemd.js`): Markdown — CommonMark with GitHub's tables and task lists — read into
 * blocks and inline parts the app draws itself (the app has no Markdown library; this minimal reader never renders raw
 * HTML and keeps only http, https and mailto links, as the plugin's renderer). Plus the line helpers the editor needs:
 * [checks], [toggle], [lineText], [linkTask], [tags], [attachments]. Pure (unit-tested).
 */
object NoteMd {

    private val CHECK_RE = Regex("^(\\s*)([-*+])\\s+\\[([ xX])\\]\\s+(.*)$")
    private val BULLET_RE = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ORDERED_RE = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val HEAD_RE = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val HR_RE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val FENCE_RE = Regex("^\\s{0,3}(```|~~~)")
    private val TABLE_SEP_RE = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val TAG_RE = Regex("(^|[\\s(,;])#([\\p{L}\\p{N}][\\p{L}\\p{N}_\\-]{0,40})")
    private val TASK_LINK_RE = Regex("\\s*\\[[^\\]]*\\]\\(task:(\\d{1,12})\\)")
    private val ATT_RE = Regex("\\]\\(att:(\\d{1,12})\\)")
    private val SAFE_URL = Regex("^(https?://|mailto:)[^\\s<>\"']+$", RegexOption.IGNORE_CASE)

    fun lines(md: String?): List<String> = md.orEmpty().replace(Regex("\\r\\n?"), "\n").split("\n")

    // ── blocks ──────────────────────────────────────────────

    sealed interface Block
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val lines: List<String>) : Block
    data class Quote(val lines: List<String>) : Block
    data class Code(val text: String) : Block
    data object Rule : Block
    data class Table(val head: List<String>, val rows: List<List<String>>) : Block
    /** A list item; [number] for an ordered one. */
    data class Item(val depth: Int, val number: Int?, val text: String) : Block
    /** A check box of source line [line] (0-based, what `POST notes/<id>/check` takes). */
    data class Check(val line: Int, val depth: Int, val done: Boolean, val text: String) : Block

    private fun depthOf(indent: String) = minOf(3, indent.replace("\t", "  ").length / 2)

    private fun cells(row: String): List<String> {
        val r = row.trim().removePrefix("|").removeSuffix("|")
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < r.length) {
            when {
                r[i] == '\\' && i + 1 < r.length && r[i + 1] == '|' -> { cur.append('|'); i++ }
                r[i] == '|' -> { out.add(cur.toString().trim()); cur.clear() }
                else -> cur.append(r[i])
            }
            i++
        }
        out.add(cur.toString().trim())
        return out
    }

    /** The note's text → its blocks (the same reading as the plugin's `render`). */
    fun blocks(md: String?): List<Block> {
        val ls = lines(md)
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) { out.add(Paragraph(para.toList())); para.clear() }
        }
        var i = 0
        while (i < ls.size) {
            val l = ls[i]
            val fence = FENCE_RE.find(l)
            if (fence != null) {
                flush()
                val f = fence.groupValues[1]
                val code = mutableListOf<String>()
                i++
                while (i < ls.size && !ls[i].trim().startsWith(f)) code.add(ls[i++])
                out.add(Code(code.joinToString("\n")))
                i++
                continue
            }
            if (l.isBlank()) { flush(); i++; continue }
            val head = HEAD_RE.matchEntire(l)
            if (head != null) {
                flush()
                out.add(Heading(minOf(head.groupValues[1].length, 3), head.groupValues[2]))
                i++
                continue
            }
            if (HR_RE.matches(l)) { flush(); out.add(Rule); i++; continue }
            if (Regex("^\\s*>").containsMatchIn(l)) {
                flush()
                val q = mutableListOf<String>()
                while (i < ls.size && Regex("^\\s*>").containsMatchIn(ls[i])) q.add(ls[i++].replace(Regex("^\\s*>\\s?"), ""))
                out.add(Quote(q))
                continue
            }
            if ('|' in l && i + 1 < ls.size && TABLE_SEP_RE.matches(ls[i + 1]) && '-' in ls[i + 1]) {
                flush()
                val cols = cells(l)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < ls.size && '|' in ls[i] && ls[i].isNotBlank()) rows.add(cells(ls[i++]))
                out.add(Table(cols, rows.map { r -> cols.indices.map { k -> r.getOrElse(k) { "" } } }))
                continue
            }
            val check = CHECK_RE.matchEntire(l)
            if (check != null) {
                flush()
                out.add(Check(i, depthOf(check.groupValues[1]), check.groupValues[3] != " ", check.groupValues[4]))
                i++
                continue
            }
            val b = BULLET_RE.matchEntire(l)
            val o = if (b == null) ORDERED_RE.matchEntire(l) else null
            if (b != null || o != null) {
                flush()
                val m = (b ?: o)!!
                out.add(Item(depthOf(m.groupValues[1]), o?.groupValues?.get(2)?.toIntOrNull(), m.groupValues[3]))
                i++
                continue
            }
            para.add(l.trim())
            i++
        }
        flush()
        return out
    }

    // ── inline ──────────────────────────────────────────────

    /** A piece of a line: text with its marks, a link, a picture or file of the note, the task a line became, a tag. */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        /** http, https or mailto only. */
        val url: String? = null,
        val att: Long? = null,
        val image: Boolean = false,
        val task: Long? = null,
        val tag: String? = null,
    )

    private const val P = '\u0001'
    private const val C = '\u0000'
    private const val B_ON = '\u0002'
    private const val B_OFF = '\u0003'
    private const val I_ON = '\u0004'
    private const val I_OFF = '\u0005'
    private const val S_ON = '\u0006'
    private const val S_OFF = '\u0007'

    /** One line's inline Markdown → its spans. */
    fun inline(text: String): List<Span> {
        val codes = mutableListOf<String>()
        var s = Regex("`([^`\\n]+)`").replace(text.replace(Regex("[\\u0000-\\u0007]"), " ")) { m -> codes.add(m.groupValues[1]); "$C${codes.size - 1}$C" }
        val parts = mutableListOf<Span>()
        s = Regex("(!?)\\[([^\\]\\n]{0,300})\\]\\(([^)\\s]{1,2000})\\)").replace(s) { m ->
            val bang = m.groupValues[1].isNotEmpty()
            val label = m.groupValues[2]
            val url = m.groupValues[3]
            val ref = Regex("^(att|task):(\\d{1,12})$").matchEntire(url)
            val span = when {
                ref != null && ref.groupValues[1] == "att" -> Span(label, att = ref.groupValues[2].toLong(), image = bang)
                ref != null -> Span(label, task = ref.groupValues[2].toLong())
                !bang && SAFE_URL.matches(url.trim()) -> Span(label.ifEmpty { url }, url = url.trim())
                else -> Span(m.value)
            }
            parts.add(span)
            "$P${parts.size - 1}$P"
        }
        s = Regex("(^|[\\s(])(https?://[^\\s<>\"']+[^\\s<>\"'.,;:!?)])").replace(s) { m ->
            parts.add(Span(m.groupValues[2], url = m.groupValues[2]))
            m.groupValues[1] + "$P${parts.size - 1}$P"
        }
        s = Regex("\\*\\*([^*\\n]+)\\*\\*").replace(s) { "$B_ON${it.groupValues[1]}$B_OFF" }
        s = Regex("__([^_\\n]+)__").replace(s) { "$B_ON${it.groupValues[1]}$B_OFF" }
        s = Regex("(^|[^*\\w])\\*([^*\\n]+)\\*(?![*\\w])").replace(s) { it.groupValues[1] + "$I_ON${it.groupValues[2]}$I_OFF" }
        s = Regex("(^|[^_\\p{L}\\p{N}])_([^_\\n]+)_(?![_\\p{L}\\p{N}])").replace(s) { it.groupValues[1] + "$I_ON${it.groupValues[2]}$I_OFF" }
        s = Regex("~~([^~\\n]+)~~").replace(s) { "$S_ON${it.groupValues[1]}$S_OFF" }
        s = TAG_RE.replace(s) { m ->
            parts.add(Span("#" + m.groupValues[2], tag = lower(m.groupValues[2])))
            m.groupValues[1] + "$P${parts.size - 1}$P"
        }
        val out = mutableListOf<Span>()
        var bold = false
        var italic = false
        var strike = false
        val buf = StringBuilder()
        fun emit() {
            if (buf.isNotEmpty()) { out.add(Span(buf.toString(), bold, italic, strike)); buf.clear() }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                B_ON, B_OFF -> { emit(); bold = c == B_ON }
                I_ON, I_OFF -> { emit(); italic = c == I_ON }
                S_ON, S_OFF -> { emit(); strike = c == S_ON }
                P, C -> {
                    val end = s.indexOf(c, i + 1)
                    val n = if (end > i) s.substring(i + 1, end).toIntOrNull() else null
                    if (n == null) { buf.append(c); i++; continue }
                    emit()
                    if (c == P) parts.getOrNull(n)?.let { out.add(it.copy(bold = it.bold || bold, italic = it.italic || italic, strike = it.strike || strike)) }
                    else codes.getOrNull(n)?.let { out.add(Span(it, code = true)) }
                    i = end + 1
                    continue
                }
                else -> buf.append(c)
            }
            i++
        }
        emit()
        return out
    }

    // ── line helpers (the plugin's checks, toggle, lineText, linkTask, tags, attachments) ──

    data class CheckLine(val line: Int, val text: String, val done: Boolean, val task: Long?)

    fun checks(md: String?): List<CheckLine> {
        val out = mutableListOf<CheckLine>()
        var fenced = false
        lines(md).forEachIndexed { i, l ->
            if (FENCE_RE.containsMatchIn(l)) { fenced = !fenced; return@forEachIndexed }
            if (fenced) return@forEachIndexed
            val m = CHECK_RE.matchEntire(l) ?: return@forEachIndexed
            val t = TASK_LINK_RE.find(m.groupValues[4])
            out.add(CheckLine(i, m.groupValues[4].replace(TASK_LINK_RE, "").trim(), m.groupValues[3] != " ", t?.groupValues?.get(1)?.toLong()))
        }
        return out
    }

    fun toggle(md: String?, line: Int, done: Boolean): String? {
        val ls = lines(md).toMutableList()
        val m = ls.getOrNull(line)?.let { CHECK_RE.matchEntire(it) } ?: return null
        ls[line] = m.groupValues[1] + m.groupValues[2] + " [" + (if (done) "x" else " ") + "] " + m.groupValues[4]
        return ls.joinToString("\n")
    }

    /** A line without its marks (the title of the task it becomes). */
    fun lineText(md: String?, line: Int): String? {
        var l = lines(md).getOrNull(line) ?: return null
        l = l.replace(TASK_LINK_RE, "")
        l = l.replace(HEAD_RE, "$2").replace(Regex("^\\s*>\\s?"), "")
        CHECK_RE.matchEntire(l)?.let { l = it.groupValues[4] } ?: BULLET_RE.matchEntire(l)?.let { l = it.groupValues[3] } ?: ORDERED_RE.matchEntire(l)?.let { l = it.groupValues[3] }
        l = l.replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1").replace(Regex("\\*\\*|__|~~|`"), "").replace(Regex("\\s+"), " ").trim()
        return l.ifEmpty { null }
    }

    fun linkTask(md: String?, line: Int, taskId: Long, label: String): String? {
        val ls = lines(md).toMutableList()
        val l = ls.getOrNull(line) ?: return null
        val lab = label.replace(Regex("[\\[\\]()]"), "").ifEmpty { "Aufgabe" }
        ls[line] = l.replace(TASK_LINK_RE, "").trimEnd() + " [$lab](task:$taskId)"
        return ls.joinToString("\n")
    }

    private fun lower(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFC)

    fun tags(md: String?): List<String> {
        val out = mutableListOf<String>()
        var fenced = false
        for (l in lines(md)) {
            if (FENCE_RE.containsMatchIn(l)) { fenced = !fenced; continue }
            if (fenced) continue
            val s = l.replace(Regex("^#{1,6}\\s+"), " ").replace(Regex("`[^`]*`"), " ").replace(Regex("\\]\\([^)]*\\)"), "]")
            TAG_RE.findAll(s).forEach { m ->
                val t = lower(m.groupValues[2])
                if (!Regex("^\\d+$").matches(t) && t !in out) out.add(t)
            }
        }
        return out.take(50)
    }

    fun attachments(md: String?): List<Long> = ATT_RE.findAll(md.orEmpty()).map { it.groupValues[1].toLong() }.distinct().toList()

    /** Whether [line] (0-based) of [md] can become a task (a text line, not a heading, rule or code). */
    fun taskable(md: String?, line: Int): Boolean = lineText(md, line) != null && !HR_RE.matches(lines(md)[line])

    // ── the editor's toolbar ────────────────────────────────

    /** The text and cursor after a toolbar action. */
    data class Edit(val text: String, val selStart: Int, val selEnd: Int)

    /** Wraps the selection (or puts the marks at the cursor) — bold `**`, italic `*`. */
    fun wrap(text: String, start: Int, end: Int, mark: String): Edit {
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(a, text.length)
        val t = text.substring(0, a) + mark + text.substring(a, b) + mark + text.substring(b)
        return Edit(t, a + mark.length, b + mark.length)
    }

    /** Puts [prefix] at the start of the cursor's line (a heading `## `, a list `- `, a check box `- [ ] `); again: off. */
    fun linePrefix(text: String, cursor: Int, prefix: String): Edit {
        val c = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', c - 1).let { if (it < 0) 0 else it + 1 }
        val rest = text.substring(lineStart)
        return if (rest.startsWith(prefix)) {
            Edit(text.substring(0, lineStart) + rest.removePrefix(prefix), maxOf(lineStart, c - prefix.length), maxOf(lineStart, c - prefix.length))
        } else {
            val known = listOf("- [ ] ", "- [x] ", "## ", "# ", "### ", "- ", "* ", "1. ").firstOrNull { rest.startsWith(it) }
            val body = if (known != null) rest.removePrefix(known) else rest
            val shift = prefix.length - (known?.length ?: 0)
            Edit(text.substring(0, lineStart) + prefix + body, c + shift, c + shift)
        }
    }

    /** A table of two columns at the cursor. */
    fun table(text: String, cursor: Int, head: List<String>): Edit {
        val c = cursor.coerceIn(0, text.length)
        val before = text.substring(0, c)
        val lead = if (before.isEmpty() || before.endsWith("\n\n")) "" else if (before.endsWith("\n")) "\n" else "\n\n"
        val t = lead + "| " + head.joinToString(" | ") + " |\n|" + head.joinToString("|") { " --- " } + "|\n|" + head.joinToString("|") { "   " } + "|\n"
        return Edit(before + t + text.substring(c), c + t.length, c + t.length)
    }

    /** Inserts [snippet] at the cursor on a line of its own (a picture `![name](att:id)`). */
    fun insertLine(text: String, cursor: Int, snippet: String): Edit {
        val c = cursor.coerceIn(0, text.length)
        val before = text.substring(0, c)
        val lead = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
        val t = lead + snippet + "\n"
        return Edit(before + t + text.substring(c), c + t.length, c + t.length)
    }

    /** The 0-based line the cursor is on. */
    fun lineOf(text: String, cursor: Int): Int = text.substring(0, cursor.coerceIn(0, text.length)).count { it == '\n' }

    /** The first line as the title, the rest as the body (a note made from a shared text). */
    fun splitTitle(text: String): Pair<String, String> {
        val t = text.trim()
        val first = t.substringBefore('\n').trim().take(120)
        return first to t.substringAfter('\n', "").trim()
    }
}
