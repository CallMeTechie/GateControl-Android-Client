package com.gatecontrol.android.notizen

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A Synology DS Note / Note Station export (`.nsx`), read on the phone — never uploaded as a whole (README "Umzug aus
 * DS Note"): a port of the plugin's `ui/nsx.js`. A ZIP (stored or deflated entries, ZIP64) with `config.json`, a JSON
 * entry per notebook and note and `file_<md5>` per attachment. [Source] reads byte ranges of the file the person picked
 * (SAF), so only the directory, the JSON entries and one attachment at a time are in memory.
 */
object Nsx {

    class NsxException(val code: String, message: String? = null) : Exception(message ?: code)

    /** Random access to the export. */
    interface Source {
        val size: Long
        /** The bytes [start] until [end] (exclusive). */
        fun read(start: Long, end: Long): ByteArray
    }

    class Bytes(private val b: ByteArray) : Source {
        override val size: Long get() = b.size.toLong()
        override fun read(start: Long, end: Long): ByteArray = b.copyOfRange(start.toInt(), end.coerceAtMost(b.size.toLong()).toInt())
    }

    data class Entry(val name: String, val method: Int, val csize: Long, val size: Long, val offset: Long)

    // ── ZIP ─────────────────────────────────────────────────

    private fun le(b: ByteArray, off: Int = 0) = ByteBuffer.wrap(b, off, b.size - off).order(ByteOrder.LITTLE_ENDIAN)
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = le(b, o).int.toLong() and 0xffffffffL
    private fun u64(b: ByteArray, o: Int): Long = u32(b, o) + u32(b, o + 4) * 4294967296L

    class Archive internal constructor(private val src: Source, val entries: List<Entry>) {
        private val byName = entries.associateBy { it.name }

        fun has(name: String) = name in byName
        fun size(name: String): Long = byName[name]?.size ?: 0

        fun bytes(name: String): ByteArray {
            val e = byName[name] ?: throw NsxException("not_found", "no entry $name")
            val h = src.read(e.offset, e.offset + 30)
            if (h.size < 30 || u32(h, 0) != 0x04034b50L) throw NsxException("invalid", "bad local header")
            val start = e.offset + 30 + u16(h, 26) + u16(h, 28)
            if (e.csize > Int.MAX_VALUE - 64 || e.size > Int.MAX_VALUE - 64) throw NsxException("too_large", name)
            val raw = src.read(start, start + e.csize)
            return when (e.method) {
                0 -> raw
                8 -> inflate(raw, e.size)
                else -> throw NsxException("unsupported", "zip method ${e.method}")
            }
        }

        fun json(name: String): JsonElement {
            if ((byName[name]?.size ?: 0) > 32L * 1024 * 1024) throw NsxException("too_large", name)
            return try {
                JsonParser.parseString(String(bytes(name), Charsets.UTF_8))
            } catch (e: NsxException) {
                throw e
            } catch (e: Exception) {
                throw NsxException("invalid", "bad JSON in $name")
            }
        }
    }

    private fun inflate(raw: ByteArray, size: Long): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(raw)
            val out = ByteArrayOutputStream(size.coerceIn(0, 64L * 1024 * 1024).toInt())
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > size + 1024) throw NsxException("invalid", "inflated too large")
            }
            return out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            throw NsxException("invalid", "bad deflate data")
        } finally {
            inf.end()
        }
    }

    /** The central directory of [src]. */
    fun open(src: Source): Archive {
        val size = src.size
        if (size <= 22) throw NsxException("invalid", "not a zip file")
        val tail = minOf(size, 65557L + 20)
        val t = src.read(size - tail, size)
        var at = -1
        for (i in t.size - 22 downTo 0) if (u32(t, i) == 0x06054b50L) { at = i; break }
        if (at < 0) throw NsxException("invalid", "not a zip file")
        val count = u16(t, at + 10).toLong()
        val cdSize = u32(t, at + 12)
        val cdOff = u32(t, at + 16)
        if ((cdOff == 0xffffffffL || count == 0xffffL) && at >= 20 && u32(t, at - 20) == 0x07064b50L) {
            val eocd64 = u64(t, at - 12)
            val z = src.read(eocd64, eocd64 + 56)
            if (z.size < 56 || u32(z, 0) != 0x06064b50L) throw NsxException("invalid", "bad zip64 record")
            return dir(src, u64(z, 48), u64(z, 40), u64(z, 32))
        }
        return dir(src, cdOff, cdSize, count)
    }

    private fun dir(src: Source, off: Long, len: Long, count: Long): Archive {
        if (len > 64L * 1024 * 1024 || off < 0 || off + len > src.size) throw NsxException("invalid", "zip directory too large")
        val d = src.read(off, off + len)
        val list = mutableListOf<Entry>()
        var p = 0
        var n = 0L
        while (n < count && p + 46 <= d.size) {
            if (u32(d, p) != 0x02014b50L) throw NsxException("invalid", "bad zip directory")
            val method = u16(d, p + 10)
            var csize = u32(d, p + 20)
            var esize = u32(d, p + 24)
            val nl = u16(d, p + 28)
            val xl = u16(d, p + 30)
            val cl = u16(d, p + 32)
            var lho = u32(d, p + 42)
            if (p + 46 + nl > d.size) break
            val name = String(d, p + 46, nl, Charsets.UTF_8)
            var x = p + 46 + nl
            val xe = minOf(x + xl, d.size)
            while (x + 4 <= xe) {
                val id = u16(d, x)
                val l = u16(d, x + 2)
                var q = x + 4
                if (id == 1) {
                    if (esize == 0xffffffffL && q + 8 <= xe) { esize = u64(d, q); q += 8 }
                    if (csize == 0xffffffffL && q + 8 <= xe) { csize = u64(d, q); q += 8 }
                    if (lho == 0xffffffffL && q + 8 <= xe) { lho = u64(d, q); q += 8 }
                }
                x += 4 + l
            }
            list.add(Entry(name, method, csize, esize, lho))
            p += 46 + nl + xl + cl
            n++
        }
        return Archive(src, list)
    }

    // ── the export ──────────────────────────────────────────

    data class Notebook(val id: String, val title: String, val notes: Int, val protectedNotes: Int)

    data class Summary(val notebooks: List<Notebook>, val notes: Int, val protectedNotes: Int, val attachments: Int, val bytes: Long, val noteIds: List<String>)

    data class Attachment(val key: String, val md5: String, val name: String, val mime: String, val size: Long, val ref: String?, val entry: String?)

    data class Note(
        val id: String,
        val notebookId: String?,
        val title: String,
        val created: Long?,
        val updated: Long?,
        val tags: List<String>,
        val encrypted: Boolean,
        val html: String,
        val attachments: List<Attachment>,
    )

    private fun ids(v: JsonElement?): List<String> =
        v?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotEmpty() } }.orEmpty()

    private fun secs(v: JsonElement?): Long? {
        val n = v?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() } ?: return null
        if (!n.isFinite() || n <= 0) return null
        return Math.round(if (n < 1e11) n * 1000 else n)
    }

    private val MIME = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp", "heic" to "image/heic", "pdf" to "application/pdf", "txt" to "text/plain")

    private fun mimeOf(type: String?, name: String): String {
        if (type != null && Regex("^[a-z]+/[A-Za-z0-9.+-]+$").matches(type)) return type
        val ext = Regex("\\.([a-z0-9]{1,5})$", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.lowercase()
        return ext?.let { MIME[it] } ?: "application/octet-stream"
    }

    private fun JsonObject.s(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    fun note(arc: Archive, id: String): Note {
        val n = arc.json(id).takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val raw = n.get("attachment")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val atts = raw.entrySet().map { (k, v) ->
            val a = v?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val md5 = a.s("md5").orEmpty()
            val name = (a.get("name")?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() } ?: "Anhang").ifEmpty { "Anhang" }.take(200)
            val size = a.get("size")?.let { runCatching { it.asLong }.getOrNull() }?.takeIf { it > 0 } ?: arc.size("file_$md5")
            Attachment(k, md5, name, mimeOf(a.s("type"), name), size, a.s("ref"), "file_$md5".takeIf { arc.has(it) })
        }
        val enc = n.get("encrypt")?.takeIf { it.isJsonPrimitive }?.let { p -> (p.asJsonPrimitive.isBoolean && p.asBoolean) || (p.asJsonPrimitive.isNumber && p.asDouble == 1.0) } ?: false
        return Note(
            id = id,
            notebookId = n.s("parent_id"),
            title = (n.get("title")?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() } ?: "").take(300),
            created = secs(n.get("ctime")),
            updated = secs(n.get("mtime")) ?: secs(n.get("ctime")),
            tags = ids(n.get("tag")).map { it.take(40) },
            encrypted = enc,
            html = n.s("content").orEmpty(),
            attachments = atts,
        )
    }

    fun summary(arc: Archive): Summary {
        val cfg = arc.json("config.json").takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val nbIds = ids(cfg.get("notebook")).toMutableList()
        val noteIds = ids(cfg.get("note"))
        data class Book(val id: String, val title: String, var notes: Int = 0, var prot: Int = 0)
        val books = LinkedHashMap<String, Book>()
        for (id in nbIds) {
            if (!arc.has(id)) continue
            val b = arc.json(id).takeIf { it.isJsonObject }?.asJsonObject
            books[id] = Book(id, (b?.s("title") ?: "").take(120).ifEmpty { "Notizbuch" })
        }
        var notes = 0
        var prot = 0
        var attachments = 0
        var bytes = 0L
        val kept = mutableListOf<String>()
        for (id in noteIds) {
            if (!arc.has(id)) continue
            val n = note(arc, id)
            notes++
            kept.add(id)
            val key = n.notebookId ?: "_"
            val b = books[n.notebookId] ?: books.getOrPut(key) { Book(key, "DS Note") }
            if (b.id !in nbIds) nbIds.add(b.id)
            b.notes++
            if (n.encrypted) { prot++; b.prot++ }
            n.attachments.forEach { a -> if (a.entry != null) { attachments++; bytes += a.size } }
        }
        val out = nbIds.mapNotNull { books[it] }.map { Notebook(it.id, it.title, it.notes, it.prot) }
        return Summary(out, notes, prot, attachments, bytes, kept)
    }

    /** The id of a notebook or note as a `ref` of the import routes (`[A-Za-z0-9:._-]`, ≤ 90). */
    fun ref(id: String): String = id.replace(Regex("[^A-Za-z0-9:._-]"), "").take(90).ifEmpty { "nb" }

    // ── HTML → Markdown ─────────────────────────────────────

    private val ENT = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "auml" to "ä", "ouml" to "ö", "uuml" to "ü",
        "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß", "euro" to "€", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "laquo" to "«", "raquo" to "»", "bdquo" to "„", "ldquo" to "“", "rdquo" to "”",
    )
    private val ENT_RE = Regex("&(#[xX][0-9a-fA-F]{1,6}|#\\d{1,7}|[a-zA-Z]{2,8});")

    private fun decode(s: String): String = ENT_RE.replace(s) { m ->
        val e = m.groupValues[1]
        if (e[0] == '#') {
            val c = if (e.length > 1 && (e[1] == 'x' || e[1] == 'X')) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
            if (c != null && c > 0 && c < 0x110000 && (c < 0xD800 || c > 0xDFFF)) String(Character.toChars(c)) else ""
        } else {
            ENT[e] ?: m.value
        }
    }

    private fun attr(tag: String, name: String): String? {
        val m = Regex("\\s" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag) ?: return null
        val g = m.groups
        return decode(g[2]?.value ?: g[3]?.value ?: g[4]?.value ?: "")
    }

    private fun mdEscape(s: String): String =
        s.replace(Regex("([\\\\`*_\\[\\]|])"), "\\\\$1").replace(Regex("^(#{1,6}\\s|>|[-+*]\\s|\\d+[.)]\\s)"), "\\\\$1")

    private val WS = Regex("\\s+")
    private val TOKENS = Regex("(<[^>]{0,4000}>)")

    private fun splitTokens(s: String): List<String> {
        val out = mutableListOf<String>()
        var last = 0
        TOKENS.findAll(s).forEach { m ->
            out.add(s.substring(last, m.range.first))
            out.add(m.value)
            last = m.range.last + 1
        }
        out.add(s.substring(last))
        return out
    }

    /** The text of a DS Note (HTML) → Markdown. [image] gives a picture's link target (`attnew:<n>`) by its `ref`. */
    fun toMarkdown(html: String?, image: (String) -> String? = { null }): String {
        val cleaned = html.orEmpty().replace(Regex("<!--[\\s\\S]*?-->"), "").replace(Regex("<(script|style)[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
        val tokens = splitTokens(cleaned)
        val out = mutableListOf<String>()
        var line = StringBuilder()
        data class ListState(val tag: String, var n: Int, val check: Boolean)
        val lists = mutableListOf<ListState>()
        var href: String? = null
        var linkText = StringBuilder()
        var table: MutableList<MutableList<String>>? = null
        var cell: MutableList<String>? = null
        fun push(s: String) {
            when {
                cell != null -> cell!!.add(s)
                href != null -> linkText.append(s)
                else -> line.append(s)
            }
        }
        fun endLine() {
            val l = line.toString().replace(Regex("[ \\t]+$"), "")
            if (l.isNotBlank()) out.add(l)
            line = StringBuilder()
        }
        fun block() {
            endLine()
            if (out.isNotEmpty() && out.last() != "") out.add("")
        }
        fun flushTable() {
            val rows = table!!.filter { it.isNotEmpty() }
            if (rows.isEmpty()) return
            val w = rows.maxOf { it.size }
            fun fmt(r: List<String>) = "| " + (0 until w).joinToString(" | ") { r.getOrElse(it) { "" } } + " |"
            out.add(fmt(rows[0]))
            out.add("|" + " --- |".repeat(w))
            rows.drop(1).forEach { out.add(fmt(it)) }
            out.add("")
        }
        for (tk in tokens) {
            if (tk.isEmpty()) continue
            if (tk[0] != '<') {
                val t = WS.replace(decode(tk), " ")
                if (t.isNotEmpty()) push(if (line.isNotEmpty() || cell != null) mdEscape(t) else mdEscape(t.trimStart()))
                continue
            }
            val m = Regex("^<\\s*(/?)\\s*([a-zA-Z0-9]+)").find(tk) ?: continue
            val close = m.groupValues[1].isNotEmpty()
            val tag = m.groupValues[2].lowercase()
            if (Regex("^h[1-6]$").matches(tag)) {
                block()
                if (!close) line = StringBuilder("#".repeat(minOf(3, tag[1].digitToInt())) + " ")
                continue
            }
            when (tag) {
                "br" -> if (cell != null) cell!!.add(" ") else endLine()
                "p", "div" -> if (cell == null) { if (close) endLine() else if (line.isNotBlank()) endLine() }
                "b", "strong" -> push("**")
                "i", "em" -> push("*")
                "s", "strike", "del" -> push("~~")
                "code" -> push("`")
                "hr" -> { block(); out.add("---"); out.add("") }
                "ul", "ol" -> if (close) {
                    if (lists.isNotEmpty()) lists.removeAt(lists.size - 1)
                    if (lists.isEmpty()) block()
                } else {
                    if (lists.isEmpty()) block() else endLine()
                    lists.add(ListState(tag, 0, Regex("checklist|todo", RegexOption.IGNORE_CASE).containsMatchIn(attr(tk, "class").orEmpty())))
                }
                "li" -> {
                    endLine()
                    if (!close) {
                        val cur = lists.lastOrNull() ?: ListState("ul", 0, false)
                        cur.n++
                        val pad = " ".repeat(maxOf(0, lists.size - 1) * 2)
                        val cls = attr(tk, "class").orEmpty()
                        val checked: Boolean? = if (cur.check || Regex("check", RegexOption.IGNORE_CASE).containsMatchIn(cls)) Regex("checked|done", RegexOption.IGNORE_CASE).containsMatchIn(cls) else null
                        line = StringBuilder(pad + (if (cur.tag == "ol") "${cur.n}. " else "- ") + (if (checked != null) "[" + (if (checked) "x" else " ") + "] " else ""))
                    }
                }
                "input" -> if (Regex("type\\s*=\\s*[\"']?checkbox", RegexOption.IGNORE_CASE).containsMatchIn(tk)) {
                    val on = Regex("\\schecked", RegexOption.IGNORE_CASE).containsMatchIn(tk)
                    val cur = line.toString()
                    if (Regex("^(\\s*)[-\\d.]+\\s(\\[[ x]\\]\\s)?$").matches(cur)) {
                        line = StringBuilder(cur.replace(Regex("\\[[ x]\\]\\s$"), "").replace(Regex("\\s*$"), " ") + "[" + (if (on) "x" else " ") + "] ")
                    } else {
                        endLine()
                        line = StringBuilder("- [" + (if (on) "x" else " ") + "] ")
                    }
                }
                "a" -> if (close) {
                    val u = href
                    if (u != null) {
                        href = null
                        val txt = linkText.toString().trim()
                        linkText = StringBuilder()
                        push(if (Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(u)) "[" + txt.ifEmpty { u } + "](" + u.replace(Regex("\\s")) { m -> m.value.toByteArray(Charsets.UTF_8).joinToString("") { "%%%02X".format(it.toInt() and 255) } } + ")" else txt)
                    }
                } else {
                    href = attr(tk, "href").orEmpty()
                    linkText = StringBuilder()
                }
                "img" -> {
                    val ref = attr(tk, "ref")
                    val target = ref?.let(image)
                    val alt = attr(tk, "alt") ?: "Bild"
                    if (target != null) push("![" + mdEscape(alt) + "](" + target + ")")
                }
                "table" -> if (close) {
                    if (table != null) flushTable()
                    table = null
                } else {
                    block()
                    table = mutableListOf()
                }
                "tr" -> if (!close) table?.add(mutableListOf())
                "td", "th" -> {
                    val tb = table
                    if (tb != null) {
                        if (close) {
                            cell?.let { c -> tb.last().add(WS.replace(c.joinToString(""), " ").trim()) }
                            cell = null
                        } else {
                            if (tb.isEmpty()) tb.add(mutableListOf())
                            cell = mutableListOf()
                        }
                    }
                }
            }
        }
        endLine()
        while (out.isNotEmpty() && out.last() == "") out.removeAt(out.size - 1)
        return out.joinToString("\n").replace(Regex("\\*\\*\\s*\\*\\*"), "").replace(Regex("\\n{3,}"), "\n\n")
    }

    // ── protected notes ─────────────────────────────────────

    private val OPENSSL = Regex("^U2FsdGVkX1[A-Za-z0-9+/=]+$")

    /**
     * A protected note's content in the CryptoJS / OpenSSL passphrase form (`U2FsdGVkX1…`: `Salted__` ‖ salt (8) ‖
     * AES-256-CBC, key and IV by EVP_BytesToKey with MD5, one round). Anything else or a wrong password: `decrypt`.
     */
    fun decrypt(cipherText: String?, password: String): String {
        val s = cipherText.orEmpty().replace(WS, "")
        if (!OPENSSL.matches(s)) throw NsxException("decrypt", "unknown format")
        val raw = try {
            Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw NsxException("decrypt", "bad base64")
        }
        if (raw.size < 32 || (raw.size - 16) % 16 != 0) throw NsxException("decrypt", "bad length")
        val salt = raw.copyOfRange(8, 16)
        val pw = password.toByteArray(Charsets.UTF_8)
        var dk = ByteArray(0)
        var prev = ByteArray(0)
        while (dk.size < 48) {
            prev = MessageDigest.getInstance("MD5").digest(prev + pw + salt)
            dk += prev
        }
        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(dk.copyOf(32), "AES"), IvParameterSpec(dk.copyOfRange(32, 48)))
            val plain = c.doFinal(raw, 16, raw.size - 16)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain)).toString()
        } catch (e: CharacterCodingException) {
            throw NsxException("decrypt", "wrong password")
        } catch (e: java.security.GeneralSecurityException) {
            throw NsxException("decrypt", "wrong password")
        } finally {
            dk.fill(0)
            pw.fill(0)
        }
    }
}
