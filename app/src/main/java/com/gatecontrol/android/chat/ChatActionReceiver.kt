package com.gatecontrol.android.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Buttons of a chat notification: the inline answer (RemoteInput → the
 * push's `reply` path, `source: notification`, with a fresh clientId so a
 * retry from the outbox is stored once), "Als gelesen markieren" (its
 * `mark_read` path and body) and the swipe-away. NOT exported: only the
 * app's own PendingIntents reach it.
 */
@AndroidEntryPoint
class ChatActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: ChatRepository
    @Inject lateinit var api: ChatApi
    @Inject lateinit var notifier: ChatNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.action ?: return
        val conv = intent.getLongExtra(EXTRA_CONVERSATION, -1L)
        if (conv <= 0) return
        val text = if (op == ACTION_REPLY) RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString() else null
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    when (op) {
                        ACTION_REPLY -> reply(conv, text)
                        ACTION_MARK_READ -> markRead(conv)
                        ACTION_DISMISSED -> notifier.dismissed(conv)
                    }
                }
            } catch (e: Exception) {
                Timber.w("Chats: notification action failed (%s)", e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun reply(conv: Long, text: String?) {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val shown = notifier.conversation(conv)
        // The push's own path (checked when it arrived); without one the conversation may not take answers.
        if (shown?.replyPath == null || ChatNotifyLogic.pathConversation(shown.replyPath) != conv) {
            notifier.replyFailed(conv, queued = false)
            return
        }
        when (val r = repository.sendFromNotification(conv, t, repository.newClientId())) {
            is ChatResult.Ok -> {
                notifier.replied(conv, t)
                // Sending moved the read mark already; mark_read acks the pushes.
                shown.readPath?.let { path -> api.readPath(path, body(shown.readBody, r.value.id)) }
            }
            ChatResult.Offline, ChatResult.NotConfigured -> notifier.replyFailed(conv, queued = true)
            is ChatResult.Failed -> notifier.replyFailed(conv, queued = r.http == 429 || r.http >= 500)
        }
    }

    private suspend fun markRead(conv: Long) {
        val shown = notifier.conversation(conv)
        val path = shown?.readPath
        if (path != null) api.readPath(path, body(shown.readBody, null))
        else repository.markRead(conv)
        notifier.markedRead(conv)
    }

    /** The push's `mark_read` body; after an own answer up to that answer. */
    private fun body(raw: String?, upTo: Long?): JsonObject {
        val o = raw?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() } ?: JsonObject()
        if (upTo != null) o.addProperty("upTo", upTo)
        return o
    }

    companion object {
        const val ACTION_REPLY = "com.gatecontrol.android.chat.REPLY"
        const val ACTION_MARK_READ = "com.gatecontrol.android.chat.MARK_READ"
        const val ACTION_DISMISSED = "com.gatecontrol.android.chat.DISMISSED"
        const val KEY_TEXT = "chat_reply_text"
        private const val EXTRA_CONVERSATION = "com.gatecontrol.android.chat.extra.CONVERSATION"
        private const val TIMEOUT_MS = 9_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

        fun intent(context: Context, op: String, conversationId: Long): Intent =
            Intent(context, ChatActionReceiver::class.java)
                .setAction(op)
                .putExtra(EXTRA_CONVERSATION, conversationId)
    }
}
