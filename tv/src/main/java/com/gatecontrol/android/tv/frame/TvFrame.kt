package com.gatecontrol.android.tv.frame

import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveMediaLogic
import kotlin.random.Random

/**
 * The photo frame of the TV (stage 2, decision TV 6): the pictures of a Drive folder as a slide show over the whole
 * screen, started by hand or when the TV sat idle in GateControl. [folderId] null: none chosen yet.
 */
data class TvFrameSettings(
    val folderId: Long? = null,
    val folderName: String? = null,
    val seconds: Int = TvFrameSchedule.DEFAULT_SECONDS,
    val shuffle: Boolean = true,
    /** Minutes without a key in GateControl before the frame starts; 0: only by hand. */
    val idleMinutes: Int = 0,
    val clock: Boolean = true,
) {
    val ready: Boolean get() = folderId != null
}

/** The timing and the order of the photo frame. Pure, unit-tested. */
object TvFrameSchedule {
    const val DEFAULT_SECONDS = 20
    /** The choices of "Wechsel alle" (Drive's own frame allows 5 … 3600 s). */
    val SECONDS = listOf(10, 20, 30, 60, 300)
    /** The choices of "Starten nach" (0: off). */
    val IDLE_MINUTES = listOf(0, 5, 10, 15, 30)
    /** The folder is read again after this long (new photos come in, deleted ones go). */
    const val REFRESH_MS = 30 * 60_000L
    /** At most this many pictures in the frame (as Drive's kiosk frame). */
    const val MAX_ITEMS = 1000

    /** The next value of [options] after [current] (OK on the row cycles); the first when [current] is not one. */
    fun <T> cycle(options: List<T>, current: T): T {
        val at = options.indexOf(current)
        return if (at < 0) options.first() else options[(at + 1) % options.size]
    }

    fun seconds(raw: Int): Int = raw.takeIf { it in SECONDS } ?: DEFAULT_SECONDS

    fun idleMinutes(raw: Int): Int = raw.takeIf { it in IDLE_MINUTES } ?: 0

    /** May the frame start now: chosen, idle start on, and no key for [idleMinutes] since [lastKeyMs]. */
    fun idleDue(settings: TvFrameSettings, lastKeyMs: Long, nowMs: Long): Boolean =
        settings.ready && settings.idleMinutes > 0 && nowMs - lastKeyMs >= settings.idleMinutes * 60_000L

    fun refreshDue(loadedAtMs: Long, nowMs: Long): Boolean = nowMs - loadedAtMs >= REFRESH_MS

    /** The pictures of a folder the frame shows: no video, nothing of a vault, nothing the virus scan holds back. */
    fun pictures(items: List<DNode>): List<DNode> = items.filter { n ->
        !n.isDir && !n.encrypted && !n.blocked && DriveLogic.kind(n) == DriveLogic.Kind.IMAGE &&
            (n.preview || n.size <= DriveMediaLogic.ORIGINAL_MAX_BYTES)
    }.take(MAX_ITEMS)
}

/**
 * The order of the pictures: by name or shuffled, every picture once before any comes again, and a new round never
 * starts with the picture the last one ended on.
 */
class TvFramePlaylist(items: List<DNode>, private val shuffle: Boolean, private val random: Random = Random.Default) {
    private var items: List<DNode> = items
    private var round: List<DNode> = emptyList()
    private var at = -1

    val size: Int get() = items.size

    /** The picture after the current one; null when the folder has none. */
    fun next(): DNode? {
        if (items.isEmpty()) return null
        if (at + 1 >= round.size) newRound()
        at++
        return round[at]
    }

    /** The picture before the current one (◀ in the frame); the same at the start of a round. */
    fun previous(): DNode? {
        if (round.isEmpty()) return next()
        if (at > 0) at--
        return round[at]
    }

    /** The folder read again: the round goes on with what is still there, new pictures come in the next round. */
    fun update(fresh: List<DNode>) {
        val ids = fresh.map { it.id }.toSet()
        // the shown ones (the current included) that are still there: the round goes on after them
        val shown = round.take(at + 1).count { it.id in ids }
        items = fresh
        round = round.filter { it.id in ids }
        at = shown - 1
    }

    private fun newRound() {
        val last = round.getOrNull(at)
        var next = if (shuffle) items.shuffled(random) else items
        if (shuffle && next.size > 1 && last != null && next.first().id == last.id) next = next.drop(1) + next.first()
        round = next
        at = -1
    }
}
