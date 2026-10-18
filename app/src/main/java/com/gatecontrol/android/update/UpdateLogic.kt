package com.gatecontrol.android.update

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Version names and codes of the app. release.yml derives the code from
 * the name as `MAJOR*10000 + MINOR*100 + PATCH` (1.46.0 → 14600), so the
 * name of an update Play offers follows from its version code.
 */
object UpdateVersions {
    fun name(code: Int): String = "${code / 10000}.${code / 100 % 100}.${code % 100}"

    /** "1.46.0" → 14600; null for anything that isn't three numbers (MINOR, PATCH < 100). */
    fun code(name: String): Int? {
        val parts = name.trim().split('.')
        if (parts.size != 3) return null
        val (major, minor, patch) = parts.map { it.toIntOrNull() ?: return null }
        if (major < 0 || minor !in 0..99 || patch !in 0..99) return null
        return major * 10000 + minor * 100 + patch
    }
}

/** One line of the changelog ("- …" under "### Features" / "### Fixes"). */
data class ReleaseNote(val text: String, val kind: Kind) {
    enum class Kind { Feature, Fix, Other }

    /** Bold part: up to the first " – " (or the whole line). */
    val title: String get() = split().first

    /** Rest after the first " – ", if any. */
    val detail: String? get() = split().second

    private fun split(): Pair<String, String?> {
        val i = text.indexOf(" – ")
        if (i <= 0) return text to null
        val rest = text.substring(i + 3).trim()
        return text.substring(0, i).trim() to rest.replaceFirstChar { it.titlecase(Locale.ROOT) }.ifEmpty { null }
    }
}

/**
 * Reads CHANGELOG.md: the sections `## [x.y.z]` newer than the installed
 * version up to (and including) the update, their `- ` bullets, cleaned up
 * (trailing PR reference dropped, first letter capitalised). Features come
 * first, each kind newest first.
 */
object ChangelogParser {
    private val heading = Regex("""^##\s+\[(\d+\.\d+\.\d+)]""")
    private val prRef = Regex("""\s*\(#\d+(?:\s*,\s*#\d+)*\)\s*$""")

    fun parse(markdown: String, currentCode: Int, targetCode: Int): List<ReleaseNote> {
        val notes = mutableListOf<ReleaseNote>()
        var inRange = false
        var kind = ReleaseNote.Kind.Other
        for (raw in markdown.lineSequence()) {
            val line = raw.trimEnd()
            if (line.startsWith("## ")) {
                val code = heading.find(line)?.groupValues?.get(1)?.let(UpdateVersions::code)
                inRange = code != null && code > currentCode && code <= targetCode
                kind = ReleaseNote.Kind.Other
                continue
            }
            if (!inRange) continue
            if (line.startsWith("### ")) {
                val name = line.removePrefix("### ").trim().lowercase(Locale.ROOT)
                kind = when {
                    name.startsWith("feature") -> ReleaseNote.Kind.Feature
                    name.startsWith("fix") || name.startsWith("bug") -> ReleaseNote.Kind.Fix
                    else -> ReleaseNote.Kind.Other
                }
                continue
            }
            if (line.startsWith("- ")) {
                val text = clean(line.removePrefix("- "))
                if (text.isNotEmpty()) notes += ReleaseNote(text, kind)
            }
        }
        return notes.sortedBy { it.kind.ordinal }
    }

    /** "story send button visible (#93)" → "Story send button visible". */
    fun clean(text: String): String =
        text.trim().replace(prRef, "").trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/**
 * Remaining download time from the measured rate: the bytes that arrived
 * within the last [windowMs] (at least [minSpanMs] of samples).
 */
class EtaEstimator(private val windowMs: Long = 15_000, private val minSpanMs: Long = 1_500) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun reset() = samples.clear()

    /** A progress report at [timeMs] (monotonic) with [bytes] downloaded so far. */
    fun add(timeMs: Long, bytes: Long) {
        // Play started over (retry): earlier samples no longer apply.
        if (samples.isNotEmpty() && bytes < samples.last().second) samples.clear()
        samples.addLast(timeMs to bytes)
        while (samples.size > 2 && timeMs - samples.first().first > windowMs) samples.removeFirst()
    }

    /** Seconds left for [totalBytes], rounded up; null while the rate isn't known. */
    fun remainingSeconds(totalBytes: Long): Int? {
        if (samples.size < 2 || totalBytes <= 0) return null
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val span = t1 - t0
        val got = b1 - b0
        if (span < minSpanMs || got <= 0) return null
        val left = (totalBytes - b1).coerceAtLeast(0)
        val bytesPerMs = got.toDouble() / span
        return ceil(left / bytesPerMs / 1000.0).toInt()
    }
}

/**
 * Written right before `completeUpdate()`: which update is being
 * installed and whether the VPN was up, so the next start can say
 * "Aktualisiert auf …" and bring the tunnel back.
 */
data class PostUpdateMarker(val fromCode: Int, val toCode: Int, val vpnWasConnected: Boolean) {
    fun encode(): String = "$fromCode|$toCode|${if (vpnWasConnected) 1 else 0}"

    companion object {
        fun decode(raw: String?): PostUpdateMarker? {
            val p = raw?.split('|') ?: return null
            if (p.size != 3) return null
            val from = p[0].toIntOrNull() ?: return null
            val to = p[1].toIntOrNull() ?: return null
            if (to <= from) return null
            return PostUpdateMarker(from, to, p[2] == "1")
        }
    }
}

object PostUpdate {
    enum class Decision {
        /** Running the version the marker was written for: show the card (once) and drop the marker. */
        Show,
        /** Still the old version (install pending or not done yet): keep the marker. */
        Keep,
        /** Unrelated version (rolled back, skipped ahead): drop it silently. */
        Discard,
    }

    fun decide(marker: PostUpdateMarker?, currentCode: Int): Decision? = when {
        marker == null -> null
        currentCode == marker.toCode -> Decision.Show
        currentCode == marker.fromCode -> Decision.Keep
        else -> Decision.Discard
    }
}

/** Formatting of sizes and times for the update screens. */
object UpdateFormat {
    private const val MB = 1_000_000.0

    /** "18 MB", "4,2 MB" (one decimal below 10 MB). */
    fun size(bytes: Long, locale: Locale): String {
        val mb = bytes / MB
        return if (mb >= 10) "${mb.roundToInt()} MB" else String.format(locale, "%.1f MB", mb)
    }

    /** "11,0 / 18,0 MB". */
    fun progress(bytes: Long, total: Long, locale: Locale): String =
        String.format(locale, "%.1f / %.1f MB", bytes / MB, total / MB)

    /** Snooze of "Später erinnern". */
    const val SNOOZE_MS: Long = 24 * 60 * 60 * 1000L

    fun snoozed(nowMs: Long, snoozeUntil: Long, snoozedCode: Int, code: Int?): Boolean =
        code != null && snoozedCode == code && nowMs < snoozeUntil

    /** "zuletzt geprüft vor …": the unit and amount for [thenMs] (null: just now). */
    fun ago(nowMs: Long, thenMs: Long): Pair<AgoUnit, Int>? {
        val minutes = ((nowMs - thenMs).coerceAtLeast(0) / 60_000).toInt()
        return when {
            minutes < 1 -> null
            minutes < 60 -> AgoUnit.Minutes to minutes
            minutes < 24 * 60 -> AgoUnit.Hours to minutes / 60
            else -> AgoUnit.Days to minutes / (24 * 60)
        }
    }

    enum class AgoUnit { Minutes, Hours, Days }
}
