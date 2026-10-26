package com.gatecontrol.android.drive

import android.provider.DocumentsContract.Document
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/**
 * Pure part of [DriveDocumentsProvider] (unit-tested): document ids, rows and names.
 *
 * Ids: `root` lists the areas, `n<id>` is a folder or file of the tree (an area's top folder is its `rootId`),
 * `p<seq>` is a file created in the Files app that goes up when it is written and closed.
 */
internal object DriveDocs {

    const val ROOT = "root"
    const val DIR = Document.MIME_TYPE_DIR
    const val OCTET = "application/octet-stream"

    sealed class Id {
        data object Root : Id()
        data class Node(val id: Long) : Id()
        data class Pending(val seq: Long) : Id()
    }

    fun parse(docId: String?): Id? = when {
        docId == ROOT -> Id.Root
        docId == null || docId.length < 2 -> null
        docId[0] == 'n' -> docId.substring(1).toLongOrNull()?.takeIf { it > 0 }?.let { Id.Node(it) }
        docId[0] == 'p' -> docId.substring(1).toLongOrNull()?.takeIf { it > 0 }?.let { Id.Pending(it) }
        else -> null
    }

    fun node(id: Long) = "n$id"

    fun pending(seq: Long) = "p$seq"

    /** One row of a cursor. [size] null: unknown. */
    data class Doc(val id: String, val name: String, val mime: String, val size: Long?, val modified: Long?, val flags: Int)

    /** What the Files app may do with [node] in an area the person can [writable] write to. */
    fun flags(node: DNode, writable: Boolean, areaRoot: Boolean): Int {
        if (!writable) return 0
        var f = if (node.isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (!areaRoot) f = f or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE
        return f
    }

    /**
     * A node as a row; an area's top folder carries the area's label. [writable]: the folder's role (a listing
     * knows it), else the area's. [mimeOf] guesses from the extension.
     */
    fun doc(node: DNode, area: DArea?, mimeOf: (String) -> String?, writable: Boolean = area?.writable == true): Doc {
        val areaRoot = area != null && area.rootId == node.id
        return Doc(
            id = node(node.id),
            name = if (areaRoot) area.label else node.name,
            mime = if (node.isDir) DIR else mime(node.mime, node.name, mimeOf),
            size = if (node.isDir) null else node.size,
            modified = millis(node.mtime) ?: millis(node.updatedAt),
            flags = flags(node, writable, areaRoot),
        )
    }

    /** An area as a folder of the root. */
    fun doc(area: DArea): Doc = Doc(
        id = node(area.rootId),
        name = area.label,
        mime = DIR,
        size = null,
        modified = null,
        flags = if (area.writable) Document.FLAG_DIR_SUPPORTS_CREATE else 0,
    )

    fun mime(mime: String?, name: String, mimeOf: (String) -> String?): String {
        val m = mime?.trim()?.lowercase(Locale.ROOT)
        if (!m.isNullOrEmpty() && m != OCTET && m.contains('/')) return m
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext.takeIf { it.isNotEmpty() }?.let(mimeOf) ?: m ?: OCTET
    }

    /** ISO 8601 → epoch millis; null when it is not one. */
    fun millis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
    }

    /**
     * The name a file created as [mime] gets: safe for Drive, with the extension of [mime] unless the name already
     * has one of that type ([extOf] / [mimeOf]: the platform's tables). Null when nothing is left.
     */
    fun createName(raw: String?, mime: String?, extOf: (String) -> String?, mimeOf: (String) -> String?): String? {
        val name = DriveLogic.safeName(raw) ?: return null
        if (mime == null || mime == DIR || mime == OCTET) return name
        val ext = extOf(mime) ?: return name
        val current = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (current == ext || (current.isNotEmpty() && mimeOf(current) == mime)) return name
        return DriveLogic.safeName("$name.$ext") ?: name
    }

    /** Folder first, then by name (the Files app sorts again as the person chose). */
    fun sorted(docs: List<Doc>): List<Doc> =
        docs.sortedWith(compareBy<Doc>({ it.mime != DIR }, { it.name.lowercase(Locale.ROOT) }))
}
