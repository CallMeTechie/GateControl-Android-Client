package com.gatecontrol.android.tv.push

import com.gatecontrol.android.notify.PluginEventSink
import com.google.gson.JsonElement
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A feature of GateControl TV that takes the realtime events of one plugin ([plugin]) from the push stream. Each one
 * binds itself into the set (`@Binds @IntoSet`, see `TvBindings`): Messenger (stage 3e), later Mail (3f), Notizen,
 * Familie — none of them has to touch the others or [TvPluginEventSink].
 *
 * Both calls come from the stream's coroutine: they return at once and do their work asynchronously.
 */
interface TvPluginListener {
    val plugin: String

    fun onEvent(type: String, data: JsonElement?)

    /** `hello` of a new connection: what happened while offline is loaded again. */
    fun onStreamOpen() {}
}

/** The TV's own [PluginEventSink]: hands each plugin's events to the listeners of that plugin. */
@Singleton
class TvPluginEventSink @Inject constructor(
    private val listeners: Set<@JvmSuppressWildcards TvPluginListener>,
) : PluginEventSink {

    override fun onPluginEvent(plugin: String, type: String, data: JsonElement?) {
        for (l in listeners) {
            if (l.plugin != plugin) continue
            runCatching { l.onEvent(type, data) }.onFailure { Timber.w("TV push: %s event not handled (%s)", plugin, it.javaClass.simpleName) }
        }
    }

    override fun onStreamOpen() {
        for (l in listeners) runCatching { l.onStreamOpen() }
    }
}
