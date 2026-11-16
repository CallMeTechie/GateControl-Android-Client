package com.gatecontrol.android.remote

import com.gatecontrol.android.notify.model.PushMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The silent signals of the plugin „Fernzugriff“ to this phone (topic `remote_signal`, contract „Fernzugriff
 * Stufe 5“ §5): a helper's question, its cancel, a void code, the end of a sharing. Consumed here, never shown
 * as a message of their own.
 */
@Singleton
class ScreenSharePush @Inject constructor(private val repository: dagger.Lazy<ScreenShareRepository>) {
    fun consume(message: PushMessage): Boolean {
        if (message.topic != RemoteJson.SIGNAL_TOPIC) return false
        RemoteJson.signal(message.dataJson)?.let { repository.get().onSignal(it) }
        return true
    }
}
