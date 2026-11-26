package com.gatecontrol.android.media

/**
 * What the app does when the original does not go through the line away (decision 89 b, stage 1): ask each time, or
 * what was chosen to keep ("merkbar").
 */
enum class MediaAwayOriginal(val wire: String) {
    /** „Wie abspielen?“ before the start. */
    ASK("ask"),

    /** „Sofort, umgewandelt“: the NAS converts to the step for the line (as "Automatisch"). */
    CONVERTED("converted"),

    /** „Original mit Mitladen“: the device loads ahead first, then it plays without stalling. */
    WAIT("wait"),

    /** „Original sofort“: it may stall, the device loads ahead meanwhile. */
    NOW("now"),
    ;

    companion object {
        fun fromWire(value: String?): MediaAwayOriginal = entries.firstOrNull { it.wire == value } ?: ASK
    }
}

/** The original away on this line: its bit rate, the line, the wait before a start without stalling, the converted step. */
data class MediaAwayFit(
    val originalBps: Long,
    val lineBps: Long,
    /** The wait for „Original mit Mitladen“; null: what has to be ahead does not fit the device's space. */
    val waitMs: Long?,
    /** The step the NAS converts to ("Sofort, umgewandelt"). */
    val convertedBps: Long,
)

/** Where loading ahead is now, for the player's card ("Auf dem Gerät mitgeladen"). */
data class MediaPlayAheadState(
    /** Bytes loaded ahead of the place the player reads. */
    val aheadBytes: Long = 0,
    /** The file's size; 0: not known yet. */
    val totalBytes: Long = 0,
    /** The byte the player reads at. */
    val headBytes: Long = 0,
    /** How fast it loads (bit/s); 0: not measured yet. */
    val rateBps: Long = 0,
    /** Loaded to the end of the file. */
    val complete: Boolean = false,
    /** Stopped at the limit (the space ahead is full). */
    val full: Boolean = false,
)

/**
 * „Beim Abspielen mitladen“ (part 2, stage 1, option C): away the player loads further ahead into a space on the
 * device (with a limit, only for the playback running, deleted after it), and before the start it is told whether the
 * original goes through the line. Pure, unit-tested.
 */
object MediaPlayAhead {
    /** The limit offered first (2 to 8 GB, as the draft says). */
    const val DEFAULT_GB = 4

    /** The limits of the setting; 0: off. */
    val LIMITS_GB = listOf(0, 2, 4, 8)

    /** Left free on the device in any case. */
    const val RESERVE_BYTES = 1L shl 30

    /** Below this there is no point (a few minutes of a film). */
    const val MIN_BYTES = 256L shl 20

    /** Of the space, this much is for what lies ahead; the rest keeps what was just seen (jumping back). */
    const val AHEAD_SHARE = 0.8

    /** Of the measured line this much is counted on for the loading (the overhead, other traffic). */
    const val LINE_SHARE = 0.85

    /** On mobile data at most this far ahead: loaded ahead and not watched costs volume. */
    const val MOBILE_AHEAD_MS = 10 * 60_000L

    /** A little more than the sum, so the end does not stall either. */
    const val START_MARGIN = 1.05

    /** The bytes of a GB setting. */
    fun gb(gb: Int): Long = gb.toLong() shl 30

    /**
     * The space for one playback: the setting, at most what is free beyond [RESERVE_BYTES] (and half of it on a small
     * device); 0 when off or when less than [MIN_BYTES] remains.
     */
    fun limitBytes(settingGb: Int, usableBytes: Long): Long {
        if (settingGb <= 0) return 0
        val free = (usableBytes - RESERVE_BYTES).coerceAtLeast(0)
        val limit = minOf(gb(settingGb), free / 2)
        return if (limit < MIN_BYTES) 0 else limit
    }

    /** How far ahead it loads at most: [AHEAD_SHARE] of [limitBytes], on mobile data [MOBILE_AHEAD_MS] of [bitrateBps]. */
    fun aheadLimit(limitBytes: Long, cellular: Boolean, bitrateBps: Long?): Long {
        val share = (limitBytes * AHEAD_SHARE).toLong()
        if (!cellular || bitrateBps == null || bitrateBps <= 0) return share
        return minOf(share, bytesFor(MOBILE_AHEAD_MS, bitrateBps))
    }

    /** The bytes [ms] of [bps] take. */
    fun bytesFor(ms: Long, bps: Long): Long = (ms / 1000.0 * bps / 8).toLong()

    /** The part of [lineBps] the loading can count on. */
    private fun usable(lineBps: Long): Double = lineBps * LINE_SHARE

    /**
     * The wait before the original of [originalBps] plays [remainingMs] to the end without stalling over [lineBps]:
     * Dauer × (1 − U / R) (the draft's formula, with [LINE_SHARE] of the line); 0 when the line carries it.
     */
    fun waitMs(remainingMs: Long, originalBps: Long, lineBps: Long): Long {
        if (remainingMs <= 0 || originalBps <= 0) return 0
        val u = usable(lineBps)
        if (u >= originalBps) return 0
        return (remainingMs * (1 - u / originalBps) * START_MARGIN).toLong()
    }

    /** The bytes that must be ahead at the start for that ([waitMs] at the line's speed). */
    fun aheadNeeded(remainingMs: Long, originalBps: Long, lineBps: Long): Long {
        if (remainingMs <= 0 || originalBps <= 0) return 0
        val missingBps = originalBps - usable(lineBps)
        if (missingBps <= 0) return 0
        return (remainingMs / 1000.0 * missingBps / 8 * START_MARGIN).toLong()
    }

    /**
     * Whether to ask „Wie abspielen?“ (decision 89 b) and with what: away, measured, the original's bit rate known
     * and more than the line carries ([MediaQuality.fits]), the source converts, the quality is "Automatisch" or
     * "Original" and loading ahead is on ([limitBytes] > 0). Null: nothing to ask (it plays as the quality says).
     */
    fun fit(
        atHome: Boolean?,
        lineBps: Long?,
        originalBps: Long?,
        remainingMs: Long?,
        setting: MediaQualitySetting,
        canConvert: Boolean,
        alwaysOriginal: Boolean,
        limitBytes: Long,
    ): MediaAwayFit? {
        if (atHome != false || limitBytes <= 0 || !canConvert || alwaysOriginal) return null
        if (setting != MediaQualitySetting.AUTO && setting != MediaQualitySetting.ORIGINAL) return null
        if (lineBps == null || lineBps <= 0 || originalBps == null || originalBps <= 0) return null
        if (MediaQuality.fits(originalBps, lineBps)) return null
        val remaining = remainingMs?.takeIf { it > 0 }
        val wait = remaining?.let { r ->
            waitMs(r, originalBps, lineBps).takeIf { aheadNeeded(r, originalBps, lineBps) <= (limitBytes * AHEAD_SHARE).toLong() }
        }
        return MediaAwayFit(originalBps, lineBps, wait, MediaQuality.step(lineBps))
    }

    /**
     * „Original mit Mitladen“: may it start now? What is ahead ([aheadBytes]) and what comes in meanwhile at
     * [rateBps] carry [bitrateBps] for the [remainingMs] left (with [START_MARGIN]); loaded to the end: yes.
     */
    fun canStart(aheadBytes: Long, remainingMs: Long, bitrateBps: Long, rateBps: Long, complete: Boolean): Boolean {
        if (complete || remainingMs <= 0 || bitrateBps <= 0) return true
        val needed = bytesFor(remainingMs, bitrateBps) * START_MARGIN
        val coming = bytesFor(remainingMs, rateBps)
        return aheadBytes + coming >= needed
    }

    /** The wait left until [canStart] at [rateBps]; null: not measured yet. */
    fun waitLeftMs(aheadBytes: Long, remainingMs: Long, bitrateBps: Long, rateBps: Long): Long? {
        if (rateBps <= 0) return null
        if (canStart(aheadBytes, remainingMs, bitrateBps, rateBps, complete = false)) return 0
        // t seconds more: ahead + rate·(t + rest) ≥ bitrate·rest·margin
        val rest = remainingMs / 1000.0
        val missing = bitrateBps / 8.0 * rest * START_MARGIN - aheadBytes - rateBps / 8.0 * rest
        return (missing / (rateBps / 8.0) * 1000).toLong().coerceAtLeast(0)
    }

    /** How long what is ahead plays ([MediaPlayAheadState.aheadBytes] at [bitrateBps]). */
    fun aheadMs(aheadBytes: Long, bitrateBps: Long?): Long? {
        if (bitrateBps == null || bitrateBps <= 0) return null
        return (aheadBytes * 8.0 / bitrateBps * 1000).toLong()
    }

    /** Minutes for the words, at least 1 ("ca. 18 Min."). */
    fun minutes(ms: Long): Long = ((ms + 59_999) / 60_000).coerceAtLeast(1)

    /**
     * The cache key of a stream: its address without what changes with each request (Jellyfin's `playSessionId`), so
     * a new choice of sound or subtitle (the same file) keeps what was loaded.
     */
    fun cacheKey(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val base = url.substring(0, q)
        val keep = url.substring(q + 1).split('&').filter { p ->
            val name = p.substringBefore('=')
            p.isNotEmpty() && !name.equals("playSessionId", ignoreCase = true)
        }
        return if (keep.isEmpty()) base else base + "?" + keep.joinToString("&")
    }
}
