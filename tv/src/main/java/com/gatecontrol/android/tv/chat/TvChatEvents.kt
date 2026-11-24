package com.gatecontrol.android.tv.chat

import com.gatecontrol.android.chat.ChatEvents
import com.gatecontrol.android.chat.MESSENGER_PLUGIN
import com.gatecontrol.android.tv.push.TvPluginListener
import com.google.gson.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/** The messenger's realtime events (`message.new`, `message.update`, …) for [TvChats]; typing is not looked at. */
@Singleton
class TvChatEvents @Inject constructor(private val chats: dagger.Lazy<TvChats>) : TvPluginListener {
    override val plugin: String = MESSENGER_PLUGIN

    override fun onEvent(type: String, data: JsonElement?) {
        val e = ChatEvents.parse(MESSENGER_PLUGIN, type, data) ?: return
        chats.get().onEvent(e)
    }

    override fun onStreamOpen() = chats.get().onStreamOpen()
}
