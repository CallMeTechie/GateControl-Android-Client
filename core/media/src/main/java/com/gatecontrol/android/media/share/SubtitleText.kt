package com.gatecontrol.android.media.share

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * A text subtitle file as the player reads it: UTF-8. Many SRT files beside a video come from Windows in Windows-1252
 * (or ISO-8859-1): read as UTF-8 their umlauts break („Gr��e“). A file that is not valid UTF-8 is taken as
 * Windows-1252 and given to the player as UTF-8; UTF-16 with its byte order mark is turned into UTF-8 too. Pure,
 * unit-tested.
 */
object SubtitleText {
    /** Text subtitle files beside a video that are read this way. */
    val EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    /** At most this many bytes are read whole (a film's SRT has a few hundred KB). */
    const val MAX_BYTES = 5 * 1024 * 1024

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** [path] names a text subtitle file ([EXTENSIONS]). */
    fun isSubtitle(path: String): Boolean = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT) in EXTENSIONS

    /** [bytes] as UTF-8: unchanged when they are UTF-8 already, else from UTF-16 (byte order mark) or Windows-1252. */
    fun toUtf8(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE).toByteArray(Charsets.UTF_8)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE).toByteArray(Charsets.UTF_8)
        }
        if (validUtf8(bytes)) return bytes
        return String(bytes, WINDOWS_1252).toByteArray(Charsets.UTF_8)
    }

    /** The bytes decode as UTF-8 without a fault (a UTF-8 byte order mark is fine). */
    fun validUtf8(bytes: ByteArray): Boolean {
        val start = if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(UTF8_BOM)) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    /** Windows-1252 (a superset of ISO-8859-1's printable characters); ISO-8859-1 where the runtime has no such charset. */
    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
}
