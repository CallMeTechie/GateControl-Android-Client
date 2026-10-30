package com.gatecontrol.android.drive

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.Locale

/**
 * A searchable PDF of scanned pages, made on the phone without any AI (Drive 1.14, D5 "printed"): each page is the
 * scanner's JPEG as it is (`DCTDecode`, nothing re-encoded), and over it the lines the phone read, as invisible text
 * (`3 Tr`) where they stand in the picture — the PDF looks like the scan, but can be searched and copied. The text uses
 * the standard font Helvetica (WinAnsi; what it cannot show becomes "?"), stretched with `Tz` to the width of the line.
 * Written straight to a stream (a scan has up to 30 pages of a few MB), unit-tested.
 */
internal object DriveSearchablePdf {

    /** A JPEG's size in pixels and its colour components (1 grey, 3 RGB, 4 CMYK). */
    data class JpegInfo(val width: Int, val height: Int, val components: Int)

    /**
     * One page: its JPEG ([length] bytes, [info]) and the lines read off it, their boxes in a picture of
     * [ocrWidth] × [ocrHeight] pixels (the text recognition may have read a scaled picture).
     */
    data class Page(val length: Long, val info: JpegInfo, val ocrWidth: Int, val ocrHeight: Int, val lines: List<DriveKi.OcrLine>)

    /** The size and colours from a JPEG's start (its SOF segment); null when it is no JPEG this writer can embed. */
    fun jpegInfo(head: ByteArray): JpegInfo? {
        fun u8(i: Int) = head[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (head.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
        var i = 2
        while (i + 3 < head.size) {
            if (u8(i) != 0xFF) return null
            val marker = u8(i + 1)
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue }
            if (marker == 0xD9 || marker == 0xDA) return null
            val len = u16(i + 2)
            if (len < 2) return null
            val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (sof) {
                if (i + 9 >= head.size) return null
                val h = u16(i + 5)
                val w = u16(i + 7)
                val c = u8(i + 9)
                return if (w > 0 && h > 0 && c in setOf(1, 3, 4)) JpegInfo(w, h, c) else null
            }
            i += 2 + len
        }
        return null
    }

    /** Width of an A4 page in points; a landscape scan gets the A4 height as its width. */
    private const val A4_SHORT = 595.28
    private const val A4_LONG = 841.89
    private val WIN_ANSI: Charset = Charset.forName("windows-1252")

    /** The page's size in points: A4 wide (landscape: A4 long), as high as the picture says. */
    fun pageSize(info: JpegInfo): Pair<Double, Double> {
        val w = if (info.width > info.height) A4_LONG else A4_SHORT
        return w to w * info.height / info.width
    }

    /**
     * Writes the PDF of [pages] to [out]: [open] gives page i's JPEG (exactly its length), [measure] the width of a
     * text at font size 1 in Helvetica-like letters (used to stretch the invisible line to its box).
     */
    fun write(out: OutputStream, pages: List<Page>, open: (Int) -> InputStream, measure: (String) -> Double = ::roughWidth) {
        require(pages.isNotEmpty())
        val w = Counting(out)
        val offsets = ArrayList<Long>()
        fun obj(n: Int, body: () -> Unit) {
            while (offsets.size < n) offsets += 0L
            offsets[n - 1] = w.count
            w.ascii("$n 0 obj\n")
            body()
            w.ascii("\nendobj\n")
        }
        w.ascii("%PDF-1.4\n")
        w.bytes(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        // 1 catalog, 2 pages, 3 font, 4 info; page i: 5+3i page, 6+3i picture, 7+3i content
        val kids = pages.indices.joinToString(" ") { "${5 + 3 * it} 0 R" }
        obj(1) { w.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj(2) { w.ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>") }
        obj(3) { w.ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>") }
        obj(4) { w.ascii("<< /Producer (GateControl) >>") }
        pages.forEachIndexed { i, p ->
            val (pw, ph) = pageSize(p.info)
            val page = 5 + 3 * i
            obj(page) {
                w.ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(pw)} ${num(ph)}] " +
                        "/Resources << /XObject << /Im0 ${page + 1} 0 R >> /Font << /F1 3 0 R >> >> /Contents ${page + 2} 0 R >>",
                )
            }
            obj(page + 1) {
                val cs = when (p.info.components) {
                    1 -> "/DeviceGray"
                    4 -> "/DeviceCMYK /Decode [1 0 1 0 1 0 1 0]"
                    else -> "/DeviceRGB"
                }
                w.ascii(
                    "<< /Type /XObject /Subtype /Image /Width ${p.info.width} /Height ${p.info.height} /ColorSpace $cs " +
                        "/BitsPerComponent 8 /Filter /DCTDecode /Length ${p.length} >>\nstream\n",
                )
                var copied = 0L
                open(i).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val take = minOf(n.toLong(), p.length - copied).toInt()
                        if (take > 0) w.bytes(buf, take)
                        copied += take
                        if (copied >= p.length) break
                    }
                }
                require(copied == p.length) { "picture shorter than announced" }
                w.ascii("\nendstream")
            }
            obj(page + 2) {
                val content = content(p, pw, ph, measure)
                w.ascii("<< /Length ${content.size} >>\nstream\n")
                w.bytes(content)
                w.ascii("\nendstream")
            }
        }
        val xref = w.count
        w.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { w.ascii(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        w.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 4 0 R >>\nstartxref\n$xref\n%%EOF\n")
        w.flush()
    }

    /** The page's drawing: the picture over the whole page, then each line as invisible text at its place. */
    fun content(p: Page, pw: Double, ph: Double, measure: (String) -> Double = ::roughWidth): ByteArray {
        val s = java.io.ByteArrayOutputStream()
        fun a(t: String) = s.write(t.toByteArray(Charsets.US_ASCII))
        a("q ${num(pw)} 0 0 ${num(ph)} 0 0 cm /Im0 Do Q\n")
        if (p.ocrWidth <= 0 || p.ocrHeight <= 0) return s.toByteArray()
        val sx = pw / p.ocrWidth
        val sy = ph / p.ocrHeight
        val lines = p.lines.filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
        if (lines.isEmpty()) return s.toByteArray()
        a("BT 3 Tr\n")
        for (l in lines) {
            val text = l.text.trim()
            val h = (l.bottom - l.top) * sy
            val size = (h * 0.85).coerceAtLeast(1.0)
            val natural = measure(text) * size
            val target = (l.right - l.left) * sx
            val stretch = if (natural > 0) (100.0 * target / natural).coerceIn(10.0, 1000.0) else 100.0
            val x = l.left * sx
            val y = ph - l.bottom * sy + h * 0.2
            a("/F1 ${num(size)} Tf ${num(stretch)} Tz 1 0 0 1 ${num(x)} ${num(y)} Tm (")
            s.write(escape(text))
            a(") Tj\n")
        }
        a("ET\n")
        return s.toByteArray()
    }

    /** [text] as a PDF string in WinAnsi: `\`, `(` and `)` escaped, other bytes outside ASCII as octal. */
    fun escape(text: String): ByteArray {
        val enc = WIN_ANSI.newEncoder()
        val out = java.io.ByteArrayOutputStream()
        for (ch in text) {
            val c = if (ch.isISOControl()) ' ' else ch
            val b = if (enc.canEncode(c)) c.toString().toByteArray(WIN_ANSI).firstOrNull() ?: '?'.code.toByte() else '?'.code.toByte()
            val v = b.toInt() and 0xFF
            when {
                v == '\\'.code || v == '('.code || v == ')'.code -> { out.write('\\'.code); out.write(v) }
                v in 0x20..0x7E -> out.write(v)
                else -> out.write(String.format(Locale.ROOT, "\\%03o", v).toByteArray(Charsets.US_ASCII))
            }
        }
        return out.toByteArray()
    }

    /** About Helvetica's widths at size 1: narrow, wide and the rest. */
    fun roughWidth(text: String): Double = text.sumOf { c ->
        when {
            c in "iljtfrI.,:;'!|" -> 0.28
            c in "mwMW" -> 0.85
            c.isUpperCase() -> 0.67
            c == ' ' -> 0.28
            else -> 0.55
        }
    }

    private fun num(v: Double): String = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    /** Counts what goes out (the offsets of the cross-reference table). */
    private class Counting(private val out: OutputStream) {
        var count = 0L
            private set

        fun ascii(s: String) = bytes(s.toByteArray(Charsets.US_ASCII))
        fun bytes(b: ByteArray, n: Int = b.size) {
            out.write(b, 0, n)
            count += n
        }
        fun flush() = out.flush()
    }
}
