package com.gatecontrol.android.tv.media

import com.gatecontrol.android.media.server.MEDIA_PLUGIN
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.tv.push.TvPluginListener
import com.google.gson.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/** The realtime events of the plugin „Medien“ on the TV: its list changed (`media.changed`), a revocation. */
@Singleton
class TvMediaEvents @Inject constructor(private val servers: dagger.Lazy<MediaServerSources>) : TvPluginListener {
    override val plugin: String = MEDIA_PLUGIN

    override fun onEvent(type: String, data: JsonElement?) = servers.get().onEvent(type, data)

    /** Reconnected: what changed meanwhile (at most every few minutes). */
    override fun onStreamOpen() = servers.get().refreshIfStale()
}
