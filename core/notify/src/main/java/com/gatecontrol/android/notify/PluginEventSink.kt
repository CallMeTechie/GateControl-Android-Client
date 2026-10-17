package com.gatecontrol.android.notify

import com.google.gson.JsonElement

/**
 * Receives the plugins' realtime events of the push stream (`event:
 * plugin`, core 1.177+) and learns when the stream (re)opened — events are
 * not replayed, so a feature that relies on them catches up over its own
 * API after every connect. Implemented by the app (e.g. the chats of the
 * messenger plugin); unknown plugins and types are ignored there.
 *
 * Both calls come from the stream's coroutine: they must return quickly and
 * do their work asynchronously.
 */
interface PluginEventSink {
    fun onPluginEvent(plugin: String, type: String, data: JsonElement?)

    /** `hello` of a new connection: what happened while offline is missing. */
    fun onStreamOpen() {}

    companion object {
        /** Ignores everything (tests, builds without plugin features). */
        val NONE: PluginEventSink = object : PluginEventSink {
            override fun onPluginEvent(plugin: String, type: String, data: JsonElement?) = Unit
        }
    }
}
