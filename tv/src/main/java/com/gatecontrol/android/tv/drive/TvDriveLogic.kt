package com.gatecontrol.android.tv.drive

import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveMediaLogic

/** One folder on the way down from an area: what the page shows on top ("Familie › Fotos › 2026"). */
data class TvDriveCrumb(val id: Long, val name: String)

/**
 * Where the Drive page is: the areas ([folders] empty) or a folder in one of them. Zurück goes one level up and, at the
 * areas, leaves the page (the rail). Pure, unit-tested.
 */
data class TvDriveStack(val folders: List<TvDriveCrumb> = emptyList()) {
    val atAreas: Boolean get() = folders.isEmpty()
    val top: TvDriveCrumb? get() = folders.lastOrNull()

    /** Into [id]; a folder already on the way goes back to it. */
    fun open(id: Long, name: String): TvDriveStack {
        val at = folders.indexOfFirst { it.id == id }
        if (at >= 0) return TvDriveStack(folders.take(at + 1))
        return TvDriveStack(folders + TvDriveCrumb(id, name))
    }

    /** One level up; null at the areas (Zurück leaves the page). */
    fun back(): TvDriveStack? = if (atAreas) null else TvDriveStack(folders.dropLast(1))

    /** The path shown on top, after the page's title. */
    fun path(): List<String> = folders.map { it.name }
}

/** What OK on an item of the Drive page does on the TV. */
enum class TvDriveOpen {
    FOLDER,

    /** The photo viewer (◀ ▶ through the pictures of the folder). */
    PHOTO,

    /** The player (next / previous: the videos of the folder). */
    VIDEO,

    /** A document with a server preview: shown as a picture (no editing, no download on the TV). */
    PREVIEW,

    /** Held back by the virus scan. */
    BLOCKED,

    /** Nothing to show on the TV (a document without preview, an archive, …). */
    NONE,
}

object TvDriveLogic {
    /** How many items a folder page loads at once (Drive answers up to 500). */
    const val PAGE = 200

    fun openOf(n: DNode): TvDriveOpen = when {
        n.isDir -> TvDriveOpen.FOLDER
        // a vault's file is ciphertext: the lists never offer one (Drive 1.11), the TV never shows one
        n.encrypted -> TvDriveOpen.NONE
        n.blocked -> TvDriveOpen.BLOCKED
        DriveLogic.kind(n) == DriveLogic.Kind.IMAGE -> TvDriveOpen.PHOTO
        DriveLogic.kind(n) == DriveLogic.Kind.VIDEO -> TvDriveOpen.VIDEO
        n.preview && n.mediaId != null -> TvDriveOpen.PREVIEW
        else -> TvDriveOpen.NONE
    }

    /**
     * The items the viewer goes through from [tapped]: the pictures of [items] for a picture, the videos for a video
     * (the remote's ◀ ▶ seek in a video, so the two are not mixed), and where [tapped] is among them. Only what the
     * viewer may show ([DriveMediaLogic.opensInViewer]); just [tapped] when it is not in [items].
     */
    fun viewerList(items: List<DNode>, tapped: DNode): Pair<List<DNode>, Int> {
        val video = DriveMediaLogic.isVideo(tapped)
        val list = items.filter { DriveMediaLogic.opensInViewer(it) && DriveMediaLogic.isVideo(it) == video }
        val at = list.indexOfFirst { it.id == tapped.id }
        return if (at < 0) listOf(tapped) to 0 else list to at
    }

    /** The areas as the phone app sorts them, without archived ones. */
    fun areas(list: List<DArea>): List<DArea> = DriveLogic.sortAreas(list.filter { !it.archived })

    /** Folders first, then pictures and videos, then the rest — each in the server's order (by name). */
    fun ordered(items: List<DNode>): List<DNode> = items.sortedBy { n ->
        when {
            n.isDir -> 0
            DriveMediaLogic.viewable(n) -> 1
            else -> 2
        }
    }

    /** The cards of "Zuletzt in Drive": at most [max], no vault's file, none twice. */
    fun recent(nodes: List<DNode>, max: Int = 12): List<DNode> =
        nodes.filter { !it.encrypted }.distinctBy { it.id }.take(max)

    /** The next index of a list of [size] after ◀ (-1) or ▶ (+1) from [at]; it stays at the ends (no wrap). */
    fun step(at: Int, delta: Int, size: Int): Int = if (size <= 0) 0 else (at + delta).coerceIn(0, size - 1)
}
