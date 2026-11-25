package com.gatecontrol.android.media.cast

import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.share.ShareIndex
import java.util.Locale

/**
 * The source and the title of a cast in both directions: what the controller sends ([ref], [item]) and which of its
 * own sources the receiver plays it from ([match], [itemId]). Pure, unit-tested.
 */
object CastSources {

    /** [source] as the receiver looks for it; never its access (token, user, password). */
    fun ref(source: MediaSource): CastSourceRef = CastSourceRef(
        kind = source.kind,
        serverId = source.serverId?.takeIf { source.kind == MediaSourceKind.JELLYFIN },
        address = source.address,
        folder = if (source.kind.share) MediaAddress.folderName(source.kind, source.address) else null,
        name = source.name,
    )

    /** [title] (a film, an episode, a video; null for a series or season) as the receiver plays it. */
    fun item(title: MediaTitle, source: MediaSource): CastItem? {
        if (!title.playable) return null
        val path = if (source.kind.share) ShareIndex.pathOf(title.id) ?: return null else null
        return CastItem(
            id = title.id,
            name = title.name,
            kind = when (title.kind) {
                MediaTitle.Kind.MOVIE -> CastItem.KIND_MOVIE
                MediaTitle.Kind.EPISODE -> CastItem.KIND_EPISODE
                else -> CastItem.KIND_VIDEO
            },
            path = path,
            seriesName = title.seriesName,
            season = title.season,
            episode = title.episode,
            runtimeMs = title.runtimeMs?.takeIf { it > 0 },
        )
    }

    /**
     * The receiver's own source for [ref]: Jellyfin by its server id, else by its address; a share by its kind and
     * address, else the share (SMB or WebDAV) with the same last folder. Null: not set up here — the receiver answers
     * [CastProtocol.ERROR_SOURCE_MISSING].
     */
    fun match(ref: CastSourceRef, sources: List<MediaSource>): MediaSource? {
        if (ref.kind == MediaSourceKind.JELLYFIN) {
            val jelly = sources.filter { it.kind == MediaSourceKind.JELLYFIN }
            ref.serverId?.let { id -> jelly.firstOrNull { it.serverId.equals(id, ignoreCase = true) }?.let { return it } }
            val address = ref.address?.let { MediaAddress.normalize(it) } ?: return null
            return jelly.firstOrNull { MediaAddress.normalize(it.address) == address }
        }
        val shares = sources.filter { it.kind.share }
        val address = ref.address?.let { MediaAddress.normalizeShare(ref.kind, it) }
        if (address != null) {
            shares.firstOrNull { it.kind == ref.kind && sameAddress(it, address) }?.let { return primary(it, sources) }
        }
        val folder = (ref.folder ?: ref.address?.let { MediaAddress.folderName(ref.kind, it) })?.lowercase(Locale.ROOT) ?: return null
        return shares.firstOrNull { MediaAddress.folderName(it.kind, it.address) == folder }?.let { primary(it, sources) }
    }

    private fun sameAddress(s: MediaSource, normalized: String): Boolean =
        (MediaAddress.normalizeShare(s.kind, s.address) ?: s.address).equals(normalized, ignoreCase = s.kind == MediaSourceKind.SMB)

    /** A pair shows (and keeps its titles) under its SMB source. */
    private fun primary(s: MediaSource, sources: List<MediaSource>): MediaSource {
        if (s.kind == MediaSourceKind.SMB) return s
        val partner = s.pairWith?.let { id -> sources.firstOrNull { it.id == id && it.kind == MediaSourceKind.SMB } }
        return partner ?: s
    }

    /** The id of [item] in the receiver's [source]: Jellyfin's own item id (the same server), a share's by its path. */
    fun itemId(source: MediaSource, item: CastItem): String? =
        if (source.kind.share) item.path?.takeIf(CastWire::safePath)?.let(ShareIndex::fileId) else item.id
}
