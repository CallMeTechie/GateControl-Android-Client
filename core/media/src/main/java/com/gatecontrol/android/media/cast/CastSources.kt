package com.gatecontrol.android.media.cast

import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.share.ShareIndex

/**
 * The source and the title of a cast in both directions: what the controller sends ([ref], [item]) and which of its
 * own sources the receiver plays it from ([match], [itemId]). Pure, unit-tested.
 */
object CastSources {

    /**
     * [source] as the receiver looks for it: the plugin's source id (only a source of the plugin can be cast, one set up
     * on this device alone has no id the receiver knows; null then). Never its access (token, user, password).
     */
    fun ref(source: MediaSource): CastSourceRef? {
        val id = source.remoteId?.takeIf { source.managed && CastProtocol.validSource(it) } ?: return null
        return CastSourceRef(
            id = id,
            kind = source.kind.wire,
            serverId = source.serverId?.takeIf { source.kind == MediaSourceKind.JELLYFIN },
            name = source.name,
        )
    }

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
     * The receiver's own source for [ref]: the source of the plugin with the same id (a share set up for SMB and WebDAV
     * plays from its SMB half, as its titles are kept there). Null: not there for this device — the receiver answers
     * [CastProtocol.ERROR_SOURCE_MISSING].
     */
    fun match(ref: CastSourceRef, sources: List<MediaSource>): MediaSource? {
        val own = sources.filter { it.managed && it.remoteId == ref.id }
        val s = own.firstOrNull { it.kind == MediaSourceKind.SMB } ?: own.firstOrNull() ?: return null
        return primary(s, sources)
    }

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
