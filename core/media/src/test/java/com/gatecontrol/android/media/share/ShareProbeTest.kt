package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaDeviceCaps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** A share's video looked into before it plays: Matroska and MP4 heads, the file name's words, what a phone cannot play. */
class ShareProbeTest {

    // ---- building small files ----

    private fun ebml(id: Long, body: ByteArray, unknownSize: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val idBytes = generateSequence(id) { it ushr 8 }.takeWhile { it != 0L }.map { (it and 0xff).toByte() }.toList().reversed()
        out.write(idBytes.toByteArray())
        if (unknownSize) {
            out.write(byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1))
        } else {
            // 8-byte size
            out.write(0x01)
            out.write(ByteBuffer.allocate(8).putLong(body.size.toLong()).array(), 1, 7)
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun str(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun u8(v: Int) = byteArrayOf(v.toByte())
    private fun f64(v: Double) = ByteBuffer.allocate(8).putDouble(v).array()
    private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }

    private fun track(type: Int, codec: String) = ebml(0xAE, cat(ebml(0x83, u8(type)), ebml(0x86, str(codec))))

    private fun mkv(vararg tracks: ByteArray, durationMs: Double = 2_520_000.0, padding: Int = 0): ByteArray {
        val info = ebml(0x1549A966, cat(ebml(0x2AD7B1, byteArrayOf(0x0F, 0x42, 0x40)), ebml(0x4489, f64(durationMs))))
        val void = if (padding > 0) ebml(0xEC, ByteArray(padding)) else ByteArray(0)
        val segment = ebml(0x18538067, cat(info, void, ebml(0x1654AE6B, cat(*tracks)), ebml(0x1F43B675, ByteArray(64), unknownSize = false)))
        return cat(ebml(0x1A45DFA3, ebml(0x4282, str("matroska"))), segment)
    }

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val b = cat(*body)
        return ByteBuffer.allocate(8).putInt(8 + b.size).put(type.toByteArray(Charsets.ISO_8859_1)).array() + b
    }

    private fun mp4Track(handler: String, fourcc: String) = box(
        "trak",
        box("tkhd", ByteArray(84)),
        box(
            "mdia",
            box("mdhd", ByteArray(24)),
            box("hdlr", ByteArray(8), handler.toByteArray(Charsets.ISO_8859_1), ByteArray(12)),
            box("minf", box("stbl", box("stsd", ByteArray(4), ByteBuffer.allocate(4).putInt(1).array(), box(fourcc, ByteArray(70))), box("stts", ByteArray(8)))),
        ),
    )

    private fun mp4(moovFirst: Boolean, vararg tracks: ByteArray): ByteArray {
        val mvhd = box("mvhd", ByteArray(12), ByteBuffer.allocate(8).putInt(1000).putInt(4_320_000).array(), ByteArray(80))
        val moov = box("moov", mvhd, *tracks)
        val mdat = box("mdat", ByteArray(200_000))
        val ftyp = box("ftyp", str("isom"), ByteArray(4))
        return if (moovFirst) cat(ftyp, moov, mdat) else cat(ftyp, mdat, moov)
    }

    private fun probe(bytes: ByteArray, name: String): ShareProbeResult? =
        ShareProbe.probe(FakeShareFs(mapOf("x/$name" to bytes)), "x/$name", name)

    // ---- tests ----

    @Test
    fun `matroska - length and codecs from its head`() {
        val r = probe(mkv(track(1, "V_MPEGH/ISO/HEVC"), track(2, "A_DTS"), track(2, "A_AC3"), track(17, "S_TEXT/UTF8")), "Silvester am See.mkv")!!
        assertEquals(2_520_000, r.durationMs)
        assertEquals("hevc", r.video)
        assertEquals(listOf("dts", "ac3"), r.audio)
        // Info and Tracks behind a large element: read in a further block
        val far = probe(mkv(track(1, "V_MPEG4/ISO/AVC"), track(2, "A_TRUEHD"), padding = 70_000), "Weit.mkv")!!
        assertEquals("h264", far.video)
        assertEquals(listOf("truehd"), far.audio)
    }

    @Test
    fun `mp4 - the boxes, also with the moov at the end`() {
        val start = probe(mp4(true, mp4Track("vide", "avc1"), mp4Track("soun", "ac-3")), "Turnier.mp4")!!
        assertEquals(4_320_000, start.durationMs)
        assertEquals("h264", start.video)
        assertEquals(listOf("ac3"), start.audio)
        val end = probe(mp4(false, mp4Track("vide", "hvc1"), mp4Track("soun", "mp4a")), "Ende.mp4")!!
        assertEquals("hevc", end.video)
        assertEquals(listOf("aac"), end.audio)
    }

    @Test
    fun `unknown containers and broken files - the file name's words`() {
        val avi = probe(ByteArray(100), "Konzert.DTS-HD.MA.avi")!!
        assertTrue(avi.fromName)
        assertEquals(listOf("dts"), avi.audio)
        assertEquals(listOf("eac3"), ShareProbe.fromName("Film.2025.DDP5.1.x265.mkv")!!.audio)
        assertEquals("hevc", ShareProbe.fromName("Film.2025.DDP5.1.x265.mkv")!!.video)
        assertEquals(listOf("ac3"), ShareProbe.fromName("Film.AC3.mkv")!!.audio)
        assertNull(ShareProbe.fromName("Geburtstag Oma.mkv"))
        // not Matroska inside: nothing known, the name says nothing either
        assertNull(probe(ByteArray(5000) { 7 }, "Kaputt.mkv"))
        // a file not there
        assertNull(ShareProbe.probe(FakeShareFs(emptyMap()), "weg.mkv"))
    }

    @Test
    fun `what the phone cannot play - DTS and TrueHD never, AC3 without a decoder, the picture without one`() {
        val plain = MediaDeviceCaps.fromDecoders(listOf("video/avc", "audio/mp4a-latm"), hdrScreen = false, name = "GateControl")
        val dolby = MediaDeviceCaps.fromDecoders(listOf("video/avc", "video/hevc", "audio/ac3", "audio/eac3"), hdrScreen = false, name = "GateControl")
        assertEquals(ShareFormatProblem(audio = "DTS"), ShareProbe.problem(ShareProbeResult(audio = listOf("dts")), dolby))
        assertEquals(ShareFormatProblem(audio = "TrueHD"), ShareProbe.problem(ShareProbeResult(audio = listOf("truehd")), dolby))
        // one track the phone plays: fine
        assertNull(ShareProbe.problem(ShareProbeResult(audio = listOf("dts", "aac")), plain))
        assertEquals(ShareFormatProblem(audio = "AC3"), ShareProbe.problem(ShareProbeResult(audio = listOf("ac3")), plain))
        assertNull(ShareProbe.problem(ShareProbeResult(audio = listOf("ac3")), dolby))
        assertEquals(ShareFormatProblem(video = "HEVC"), ShareProbe.problem(ShareProbeResult(video = "hevc", audio = listOf("aac")), plain))
        assertNull(ShareProbe.problem(null, plain))
        assertNull(ShareProbe.problem(ShareProbeResult(durationMs = 1), plain))
    }

    @Test
    fun `the blocks are read once each and never more than the budget`() {
        var loads = 0
        val blocks = ShareProbe.Blocks { pos, max ->
            loads++
            ByteArray(minOf(max.toLong(), 1_000_000 - pos).toInt()) { (it % 7).toByte() }
        }
        assertEquals(10, blocks.read(0, 10).size)
        assertEquals(100, blocks.read(ShareProbe.BLOCK - 50L, 100).size)
        assertEquals(2, loads)
        blocks.read(0, 20)
        assertEquals(2, loads)
        var failed = false
        try {
            for (i in 0 until ShareProbe.MAX_BLOCKS + 2) blocks.read(i.toLong() * ShareProbe.BLOCK, 1)
        } catch (e: java.io.IOException) {
            failed = true
        }
        assertTrue(failed)
    }
}
