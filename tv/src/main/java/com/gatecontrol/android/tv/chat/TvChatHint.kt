package com.gatecontrol.android.tv.chat

import android.content.Context
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.doorbell.TvForeground
import com.gatecontrol.android.tv.push.TvPushPresenter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * „Neue Nachricht von Mila“ over other apps (Frage 18 a): a small system notification through [TvPushPresenter] with
 * the sender's name only — never the text. Off by default; quiet hours; the doorbell comes first ([TvChatLogic.hintShows]).
 */
@Singleton
class TvChatHint @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chats: TvChats,
    private val settings: TvSettings,
    private val presenter: TvPushPresenter,
) {
    private val last = ConcurrentHashMap<Long, Long>()

    fun start(scope: CoroutineScope) {
        scope.launch { chats.arrivals.collect { onArrival(it) } }
    }

    private fun onArrival(a: TvChatArrival) {
        val d = chats.data.value
        val visible = TvChatLogic.visible(d.conversations, settings.chats.value)
        val c = visible.firstOrNull { it.id == a.conversationId }
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val shows = TvChatLogic.hintShows(
            settings = settings.chatHint.value,
            visible = c != null,
            appInFront = TvForeground.inFront,
            doorbellRecent = now - presenter.lastDoorbellAt < TvChatLogic.DOORBELL_FIRST_MS,
            minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            lastHintAt = last[a.conversationId],
            now = now,
        )
        if (!shows || c == null) return
        last[a.conversationId] = now
        val name = TvChatLogic.hintName(c, a.messageId, context.getString(R.string.tv_chats_unnamed))
        presenter.chatHint(a.conversationId, context.getString(R.string.tv_chats_hint, name))
    }
}
