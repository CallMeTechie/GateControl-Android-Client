package com.gatecontrol.android.rdp.freerdp

import com.freerdp.freerdpcore.domain.BookmarkBase
import com.freerdp.freerdpcore.domain.ManualBookmark
import com.gatecontrol.android.rdp.RdpConnectionParams

/**
 * Translates a typed [RdpConnectionParams] bundle into a
 * [com.freerdp.freerdpcore.domain.ManualBookmark] that `LibFreeRDP.setConnectionInfo`
 * can consume.
 *
 * MVP scope: hostname, port, credentials, screen resolution, color depth,
 * performance flags (bandwidth-safe defaults), console-mode toggle. Clipboard,
 * drive, audio, and gateway redirection are out of scope for the first release.
 */
object RdpBookmarkBuilder {

    fun build(params: RdpConnectionParams): BookmarkBase {
        val bookmark = ManualBookmark()

        bookmark.label = params.routeName
        bookmark.hostname = params.host
        bookmark.port = params.port
        bookmark.username = params.username ?: ""
        bookmark.password = params.password ?: ""
        bookmark.domain = params.domain ?: ""

        val screen = bookmark.screenSettings
        screen.width = params.resolutionWidth.coerceAtLeast(800)
        screen.height = params.resolutionHeight.coerceAtLeast(600)
        screen.colors = params.colorDepth
        bookmark.screenSettings = screen

        val perf = bookmark.performanceFlags
        perf.remoteFX = false
        // Enable GFX pipeline for H.264/AVC compression — dramatically reduces
        // bandwidth and CPU vs raw bitmap updates. Falls back gracefully if
        // the server doesn't support it.
        perf.gfx = true
        perf.h264 = false  // let server negotiate; AVC444 may not be available
        perf.wallpaper = false
        perf.theming = false
        perf.fullWindowDrag = false
        perf.menuAnimations = false
        perf.fontSmoothing = true
        perf.desktopComposition = false
        bookmark.performanceFlags = perf

        val advanced = bookmark.advancedSettings
        advanced.consoleMode = params.adminSession
        bookmark.advancedSettings = advanced

        return bookmark
    }
}
