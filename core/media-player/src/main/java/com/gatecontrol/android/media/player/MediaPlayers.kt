package com.gatecontrol.android.media.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaPlan
import com.gatecontrol.android.media.MediaSideSubtitle
import com.gatecontrol.android.media.MediaSource

/**
 * The Media3 parts of a media player, the same for the TV and the phone: how the bytes come (Jellyfin over the
 * hub's connection with its `Authorization` header, a share through its own files), the player itself and the item
 * of a [MediaPlan] (HLS when Jellyfin converts, its subtitles beside the video).
 */
@OptIn(UnstableApi::class)
object MediaPlayers {

    /**
     * Where [source]'s videos are read: a share's file through its own connection (SMB, or WebDAV with its access) at
     * the player's place, asked at each open (a pair changes from SMB to WebDAV with the network); Jellyfin over
     * [MediaHub.http] (at home straight through the Wi-Fi) with its header.
     */
    fun dataSource(hub: MediaHub, source: MediaSource): DataSource.Factory {
        if (source.kind.share) return ShareDataSource.Factory { hub.library(source).files() }
        return OkHttpDataSource.Factory(hub.http(source)).setDefaultRequestProperties(hub.library(source).headers())
    }

    /** A player reading through [factory]. */
    fun player(context: Context, factory: DataSource.Factory): ExoPlayer =
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()

    /**
     * The item of [plan] (with [mediaId]): HLS when converted, the side subtitles with it (Jellyfin's shown at once,
     * the subtitle files beside a share's video only offered).
     */
    fun item(plan: MediaPlan, mediaId: String): MediaItem {
        val builder = MediaItem.Builder().setUri(plan.url).setMediaId(mediaId)
        if (plan.hls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (plan.sideSubtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                plan.sideSubtitles.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
                        .setMimeType(sub.mime)
                        .setLanguage(sub.language)
                        .setLabel(sub.label)
                        .setSelectionFlags(flags(sub))
                        .build()
                },
            )
        }
        return builder.build()
    }

    /** Media3's selection flags of a side subtitle. */
    internal fun flags(sub: MediaSideSubtitle): Int =
        (if (sub.default) C.SELECTION_FLAG_DEFAULT else 0) or (if (sub.forced) C.SELECTION_FLAG_FORCED else 0)
}
