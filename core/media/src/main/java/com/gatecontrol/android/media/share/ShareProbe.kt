package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import java.io.IOException
import java.util.Locale

/**
 * What a share's video holds, as far as its first bytes tell: the length, the picture's codec, the sound's codecs
 * ("hevc", "h264", "dts", "truehd", "ac3", "aac" …). [fromName]: only guessed from the file name.
 */
data class ShareProbeResult(
    val durationMs: Long? = null,
    val video: String? = null,
    val audio: List<String> = emptyList(),
    val fromName: Boolean = false,
)

/** Sound or picture the phone cannot play ("DTS", "HEVC"): played anyway, then without sound or picture. */
data class ShareFormatProblem(val audio: String? = null, val video: String? = null)

/**
 * A share's video looked into without playing it (stage 4d: "Ton DTS: am Handy kein Ton" before the start): Matroska
 * (mkv, webm) by its EBML head — Info for the length, Tracks for the codecs, through the SeekHead when they lie
 * further on — and MP4/MOV by its boxes (mvhd, each trak's hdlr and stsd), read in a few blocks of [BLOCK] bytes,
 * never more than [MAX_BLOCKS]. Other containers and anything not found: the file name's words (`…DTS-HD…`).
 */
object ShareProbe {
    const val BLOCK = 64 * 1024
    const val MAX_BLOCKS = 12

    private val NAME_AUDIO = listOf(
        Regex("(?i)(?:^|[ ._\\-\\[(])(dts(?:-?hd)?(?:[ ._-]?ma)?|dtsx?)(?=$|[ ._\\-\\])])") to "dts",
        Regex("(?i)(?:^|[ ._\\-\\[(])truehd(?=$|[ ._\\-\\])])") to "truehd",
        Regex("(?i)(?:^|[ ._\\-\\[(])(e-?ac-?3|ddp|dd\\+)(?:[ ._]?[0-9][ ._][0-9])?(?=$|[ ._\\-\\])])") to "eac3",
        Regex("(?i)(?:^|[ ._\\-\\[(])(ac-?3|dd)(?:[ ._]?[0-9][ ._][0-9])?(?=$|[ ._\\-\\])])") to "ac3",
    )
    private val NAME_VIDEO = listOf(
        Regex("(?i)(?:^|[ ._\\-\\[(])(hevc|x265|h\\.?265)(?=$|[ ._\\-\\])])") to "hevc",
        Regex("(?i)(?:^|[ ._\\-\\[(])av1(?=$|[ ._\\-\\])])") to "av1",
    )

    /** The words of the codecs for the hint ("DTS", "Dolby TrueHD"). */
    fun label(codec: String): String = when (codec) {
        "dts" -> "DTS"
        "truehd" -> "TrueHD"
        "ac3" -> "AC3"
        "eac3" -> "E-AC3"
        "hevc" -> "HEVC"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        "mpeg2" -> "MPEG-2"
        "vc1" -> "VC-1"
        else -> codec.uppercase(Locale.ROOT)
    }

    /** What the file name says (only when nothing better is known). */
    fun fromName(name: String): ShareProbeResult? {
        val base = name.substringBeforeLast('.')
        val audio = NAME_AUDIO.firstOrNull { it.first.containsMatchIn(base) }?.second
        val video = NAME_VIDEO.firstOrNull { it.first.containsMatchIn(base) }?.second
        if (audio == null && video == null) return null
        return ShareProbeResult(video = video, audio = listOfNotNull(audio), fromName = true)
    }

    /**
     * What the phone with [caps] cannot play of [r]: the sound when no track of it can be played (DTS and TrueHD
     * never, Dolby Digital (Plus) without a decoder), the picture without a decoder for it. Null: all fine or unknown.
     */
    fun problem(r: ShareProbeResult?, caps: JellyfinDeviceCaps): ShareFormatProblem? {
        if (r == null) return null
        val audio = r.audio.takeIf { list -> list.isNotEmpty() && list.none { playableAudio(it, caps) } }?.first()
        val video = r.video?.takeIf { !playableVideo(it, caps) }
        if (audio == null && video == null) return null
        return ShareFormatProblem(audio?.let(::label), video?.let(::label))
    }

    private fun playableAudio(codec: String, caps: JellyfinDeviceCaps): Boolean = when (codec) {
        "dts", "truehd" -> false
        "ac3" -> caps.ac3
        "eac3" -> caps.eac3
        else -> true
    }

    private fun playableVideo(codec: String, caps: JellyfinDeviceCaps): Boolean = when (codec) {
        "hevc" -> caps.hevc
        "av1" -> caps.av1
        "vp9" -> caps.vp9
        "vc1" -> false
        else -> true
    }

    /**
     * [path] of [fs] looked into ([name] the file's name, for its container and as the last hint). Blocking; null
     * when nothing is known. Never throws.
     */
    fun probe(fs: ShareFs, path: String, name: String = SharePaths.name(path)): ShareProbeResult? {
        val read = Blocks { position, max ->
            fs.open(path, position).use { input ->
                val out = ByteArray(max)
                var n = 0
                while (n < max) {
                    val r = input.stream.read(out, n, max - n)
                    if (r < 0) break
                    n += r
                }
                out.copyOf(n)
            }
        }
        val found = try {
            when (ShareNames.extension(name)) {
                "mkv", "webm", "mka", "mk3d" -> matroska(read)
                "mp4", "m4v", "mov", "3gp" -> mp4(read)
                else -> null
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
        val guess = fromName(name)
        if (found == null) return guess
        // the container told the length but not the codecs (a track beyond the blocks): the name's words for them
        return found.copy(
            video = found.video ?: guess?.video,
            audio = found.audio.ifEmpty { guess?.audio.orEmpty() },
        )
    }

    /** The bytes of a file in blocks, read once each, at most [MAX_BLOCKS] (a share away: one request each). */
    class Blocks(private val load: (position: Long, max: Int) -> ByteArray) {
        private val blocks = HashMap<Long, ByteArray>()

        /** [length] bytes from [position], fewer at the file's end; IOException past [MAX_BLOCKS]. */
        fun read(position: Long, length: Int): ByteArray {
            require(position >= 0 && length >= 0)
            val out = java.io.ByteArrayOutputStream(length)
            var pos = position
            var left = length
            while (left > 0) {
                val start = pos / BLOCK * BLOCK
                val block = blocks[start] ?: run {
                    if (blocks.size >= MAX_BLOCKS) throw IOException("probe budget")
                    load(start, BLOCK).also { blocks[start] = it }
                }
                val from = (pos - start).toInt()
                if (from >= block.size) break
                val n = minOf(left, block.size - from)
                out.write(block, from, n)
                pos += n
                left -= n
                if (block.size < BLOCK) {
                    if (from + n >= block.size) break
                }
            }
            return out.toByteArray()
        }
    }

    // ---- Matroska ----

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val INFO = 0x1549A966L
    private const val TIMECODE_SCALE = 0x2AD7B1L
    private const val DURATION = 0x4489L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_TYPE = 0x83L
    private const val CODEC_ID = 0x86L
    private const val CLUSTER = 0x1F43B675L

    private class Element(val id: Long, val dataStart: Long, val size: Long) {
        val end: Long get() = if (size < 0) Long.MAX_VALUE else dataStart + size
    }

    /** An element's id and size at [pos]; null past the bytes read. */
    private fun element(read: Blocks, pos: Long): Element? {
        val head = read.read(pos, 12)
        if (head.isEmpty()) return null
        val idLen = vintLength(head[0])
        if (idLen !in 1..4 || head.size < idLen + 1) return null
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (head[i].toLong() and 0xff)
        val sizeLen = vintLength(head[idLen])
        if (sizeLen !in 1..8 || head.size < idLen + sizeLen) return null
        var size = (head[idLen].toLong() and 0xff) and (0xff shr sizeLen).toLong()
        var allOnes = size == (0xff shr sizeLen).toLong()
        for (i in 1 until sizeLen) {
            val b = head[idLen + i].toLong() and 0xff
            if (b != 0xffL) allOnes = false
            size = (size shl 8) or b
        }
        return Element(id, pos + idLen + sizeLen, if (allOnes) -1 else size)
    }

    private fun vintLength(b: Byte): Int {
        val v = b.toInt() and 0xff
        if (v == 0) return 0
        return Integer.numberOfLeadingZeros(v) - 23
    }

    private fun children(read: Blocks, parent: Element, limit: Int = 256): List<Element> {
        val out = ArrayList<Element>()
        var pos = parent.dataStart
        while (pos < parent.end && out.size < limit) {
            val e = element(read, pos) ?: break
            out += e
            if (e.size < 0) break
            pos = e.end
        }
        return out
    }

    private fun uint(read: Blocks, e: Element): Long {
        if (e.size !in 1..8) return 0
        return read.read(e.dataStart, e.size.toInt()).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) }
    }

    private fun float(read: Blocks, e: Element): Double? {
        val b = read.read(e.dataStart, e.size.toInt().coerceAtMost(8))
        return when (b.size) {
            4 -> java.lang.Float.intBitsToFloat(b.fold(0) { a, x -> (a shl 8) or (x.toInt() and 0xff) }).toDouble()
            8 -> java.lang.Double.longBitsToDouble(b.fold(0L) { a, x -> (a shl 8) or (x.toLong() and 0xff) })
            else -> null
        }
    }

    internal fun matroska(read: Blocks): ShareProbeResult? {
        val ebml = element(read, 0) ?: return null
        if (ebml.id != EBML || ebml.size < 0) return null
        val segment = element(read, ebml.end) ?: return null
        if (segment.id != SEGMENT) return null
        var info: Element? = null
        var tracks: Element? = null
        var seekHead: Element? = null
        var pos = segment.dataStart
        var n = 0
        while (pos < segment.end && n++ < 64 && (info == null || tracks == null)) {
            val e = element(read, pos) ?: break
            when (e.id) {
                INFO -> info = e
                TRACKS -> tracks = e
                SEEK_HEAD -> if (seekHead == null) seekHead = e
                CLUSTER -> break
            }
            if (e.size < 0) break
            pos = e.end
            // the rest lies beyond what is cheap to read: the SeekHead says where
            if (pos >= BLOCK.toLong() * 2) break
        }
        if ((info == null || tracks == null) && seekHead != null) {
            for (seek in children(read, seekHead).filter { it.id == SEEK }) {
                val parts = children(read, seek)
                val id = parts.firstOrNull { it.id == SEEK_ID }?.let { uint(read, it) } ?: continue
                val at = parts.firstOrNull { it.id == SEEK_POSITION }?.let { uint(read, it) } ?: continue
                if (id != INFO && id != TRACKS) continue
                if (id == INFO && info != null || id == TRACKS && tracks != null) continue
                val e = runCatching { element(read, segment.dataStart + at) }.getOrNull() ?: continue
                if (e.id == INFO) info = e
                if (e.id == TRACKS) tracks = e
            }
        }
        var duration: Long? = null
        info?.let { i ->
            val parts = children(read, i)
            val scale = parts.firstOrNull { it.id == TIMECODE_SCALE }?.let { uint(read, it) }?.takeIf { it > 0 } ?: 1_000_000L
            val d = parts.firstOrNull { it.id == DURATION }?.let { float(read, it) }
            if (d != null && d > 0 && !d.isNaN()) duration = (d * scale / 1_000_000.0).toLong()
        }
        var video: String? = null
        val audio = ArrayList<String>()
        tracks?.let { t ->
            for (entry in children(read, t).filter { it.id == TRACK_ENTRY }) {
                val parts = children(read, entry)
                val type = parts.firstOrNull { it.id == TRACK_TYPE }?.let { uint(read, it) }
                val codec = parts.firstOrNull { it.id == CODEC_ID }?.let { String(read.read(it.dataStart, it.size.toInt().coerceAtMost(64)), Charsets.US_ASCII).trimEnd('\u0000') } ?: continue
                when (type) {
                    1L -> if (video == null) video = matroskaVideo(codec)
                    2L -> matroskaAudio(codec)?.let { if (it !in audio) audio += it }
                }
            }
        }
        if (duration == null && video == null && audio.isEmpty()) return null
        return ShareProbeResult(duration, video, audio)
    }

    internal fun matroskaVideo(codec: String): String? = when {
        codec.startsWith("V_MPEGH/ISO/HEVC") -> "hevc"
        codec.startsWith("V_MPEG4/ISO/AVC") -> "h264"
        codec == "V_AV1" -> "av1"
        codec == "V_VP9" -> "vp9"
        codec == "V_VP8" -> "vp8"
        codec.startsWith("V_MPEG2") || codec.startsWith("V_MPEG1") -> "mpeg2"
        codec.startsWith("V_MPEG4/ISO") -> "mpeg4"
        else -> null
    }

    internal fun matroskaAudio(codec: String): String? = when {
        codec.startsWith("A_DTS") -> "dts"
        codec.startsWith("A_TRUEHD") || codec.startsWith("A_MLP") -> "truehd"
        codec.startsWith("A_EAC3") -> "eac3"
        codec.startsWith("A_AC3") -> "ac3"
        codec.startsWith("A_AAC") -> "aac"
        codec == "A_OPUS" -> "opus"
        codec == "A_VORBIS" -> "vorbis"
        codec == "A_FLAC" -> "flac"
        codec == "A_MPEG/L3" -> "mp3"
        codec == "A_MPEG/L2" -> "mp2"
        codec.startsWith("A_PCM") -> "pcm"
        else -> null
    }

    // ---- MP4 / MOV ----

    private class Box(val type: String, val start: Long, val dataStart: Long, val end: Long)

    private fun box(read: Blocks, pos: Long, parentEnd: Long): Box? {
        val h = read.read(pos, 16)
        if (h.size < 8) return null
        var size = int32(h, 0)
        val type = String(h, 4, 4, Charsets.ISO_8859_1)
        var header = 8
        if (size == 1L) {
            if (h.size < 16) return null
            size = (int32(h, 8) shl 32) or int32(h, 12)
            header = 16
        } else if (size == 0L) {
            size = parentEnd - pos
        }
        if (size < header) return null
        return Box(type, pos, pos + header, minOf(pos + size, parentEnd))
    }

    private fun int32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)

    private fun boxes(read: Blocks, from: Long, end: Long, limit: Int = 64): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        while (pos < end && out.size < limit) {
            val b = box(read, pos, end) ?: break
            out += b
            if (b.end <= pos) break
            pos = b.end
        }
        return out
    }

    internal fun mp4(read: Blocks): ShareProbeResult? {
        val top = boxes(read, 0, Long.MAX_VALUE, limit = 16)
        if (top.firstOrNull()?.type !in setOf("ftyp", "moov", "wide", "free", "mdat")) return null
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val parts = boxes(read, moov.dataStart, moov.end)
        var duration: Long? = null
        parts.firstOrNull { it.type == "mvhd" }?.let { m ->
            val b = read.read(m.dataStart, 32)
            if (b.size >= 20) {
                val v1 = b[0].toInt() == 1
                val scale = if (v1) int32(b, 20) else int32(b, 12)
                val d = if (v1 && b.size >= 32) (int32(b, 24) shl 32) or int32(b, 28) else int32(b, 16)
                if (scale > 0 && d > 0) duration = d * 1000 / scale
            }
        }
        var video: String? = null
        val audio = ArrayList<String>()
        for (trak in parts.filter { it.type == "trak" }) {
            val mdia = boxes(read, trak.dataStart, trak.end).firstOrNull { it.type == "mdia" } ?: continue
            val inMdia = boxes(read, mdia.dataStart, mdia.end)
            val handler = inMdia.firstOrNull { it.type == "hdlr" }?.let { String(read.read(it.dataStart + 8, 4), Charsets.ISO_8859_1) }
            val minf = inMdia.firstOrNull { it.type == "minf" } ?: continue
            val stbl = boxes(read, minf.dataStart, minf.end).firstOrNull { it.type == "stbl" } ?: continue
            val stsd = boxes(read, stbl.dataStart, stbl.end, limit = 4).firstOrNull { it.type == "stsd" } ?: continue
            val entry = box(read, stsd.dataStart + 8, stsd.end) ?: continue
            when (handler) {
                "vide" -> if (video == null) video = mp4Video(entry.type)
                "soun" -> mp4Audio(entry.type)?.let { if (it !in audio) audio += it }
            }
        }
        if (duration == null && video == null && audio.isEmpty()) return null
        return ShareProbeResult(duration, video, audio)
    }

    internal fun mp4Video(fourcc: String): String? = when (fourcc) {
        "avc1", "avc3" -> "h264"
        "hvc1", "hev1", "dvh1", "dvhe" -> "hevc"
        "av01" -> "av1"
        "vp09" -> "vp9"
        "mp4v" -> "mpeg4"
        else -> null
    }

    internal fun mp4Audio(fourcc: String): String? = when (fourcc) {
        "mp4a" -> "aac"
        "ac-3" -> "ac3"
        "ec-3" -> "eac3"
        "dtsc", "dtsh", "dtsl", "dtse", "dtsx" -> "dts"
        "mlpa" -> "truehd"
        "Opus" -> "opus"
        "fLaC" -> "flac"
        ".mp3" -> "mp3"
        "lpcm", "sowt", "twos" -> "pcm"
        else -> null
    }
}
