package com.gatecontrol.android.tv.mail

import com.gatecontrol.android.mail.MAIL_PLUGIN
import com.gatecontrol.android.tv.push.TvPluginListener
import com.google.gson.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/** The Mail plugin's realtime events (`mail`: a mail came or changed) for [TvMail]. */
@Singleton
class TvMailEvents @Inject constructor(private val mail: dagger.Lazy<TvMail>) : TvPluginListener {
    override val plugin: String = MAIL_PLUGIN

    override fun onEvent(type: String, data: JsonElement?) = mail.get().onEvent(type, data)

    override fun onStreamOpen() = mail.get().onStreamOpen()
}
