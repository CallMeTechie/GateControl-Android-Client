package com.gatecontrol.android.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.graphics.drawable.IconCompat
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.notify.PushChannels
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.profile.AvatarLoader
import com.gatecontrol.android.push.PushOpenActivity
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat notifications (core contract "Darstellung Android"): own channel
 * "Chats", one MessagingStyle notification per conversation holding its
 * newest messages, an inline reply (RemoteInput → the push's `reply` path)
 * and "Als gelesen markieren" (its `mark_read` path). Nothing is shown
 * for the conversation that is open on screen. What is shown survives a
 * restart of the process (SharedPreferences), so read events and acks
 * still find their seqs.
 */
@Singleton
class ChatNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val avatars: AvatarLoader,
    private val pushRepository: dagger.Lazy<PushRepository>,
    private val repository: dagger.Lazy<ChatRepository>,
    private val shortcuts: ChatShortcuts,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    private var state: Map<Long, ChatNotifConversation> = ChatNotifyLogic.fromJson(prefs.getString(KEY_STATE, null))

    private fun save() {
        prefs.edit { putString(KEY_STATE, ChatNotifyLogic.toJson(state)) }
    }

    private suspend fun localized(): Context {
        val lang = runCatching { settingsRepository.getLocale().first() }.getOrNull() ?: return context
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return context.createConfigurationContext(config)
    }

    private fun ensureChannel(ctx: Context) {
        if (nm.getNotificationChannelGroup(GROUP_CHANNELS) == null) {
            nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_CHANNELS, ctx.getString(R.string.push_channel_group)))
        }
        // The importance of an existing channel is the person's.
        val channel = NotificationChannel(CHANNEL_ID, ctx.getString(R.string.chat_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            group = GROUP_CHANNELS
            description = ctx.getString(R.string.chat_channel_desc)
        }
        nm.createNotificationChannel(channel)
        // Messenger 1.4: groups on a channel of their own (sound / vibration per type in the Android settings).
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GROUPS, ctx.getString(R.string.chat_channel_groups), NotificationManager.IMPORTANCE_HIGH).apply {
                group = GROUP_CHANNELS
                description = ctx.getString(R.string.chat_channel_groups_desc)
            },
        )
    }

    /** Creates the chat channels (the settings open them for sound and vibration). */
    suspend fun ensureChannels() = ensureChannel(localized())

    /** A chat push: appended to its conversation's notification (or nothing while that chat is open). */
    suspend fun show(message: PushMessage, quiet: Boolean) {
        val payload = ChatNotifyLogic.payload(message) ?: return
        val conv = payload.conversationId
        if (repository.get().activeConversation.value == conv) {
            // Read right where it is shown.
            pushRepository.get().ackChatRead(listOf(message.seq))
            return
        }
        val ctx = localized()
        val timeMs = runCatching { Instant.parse(message.createdAt).toEpochMilli() }.getOrNull() ?: System.currentTimeMillis()
        val senderName = payload.senderName ?: message.title
        val text = message.body.ifBlank { message.title }
        val line = ChatNotifLine(
            seq = message.seq,
            pushId = message.id,
            senderKey = payload.senderId?.toString() ?: "gatecontrol",
            senderName = senderName,
            avatarUrl = payload.senderAvatarUrl,
            text = text,
            timeMs = timeMs,
        )
        val updated = mutex.withLock {
            val next = ChatNotifyLogic.add(state[conv], payload, line)
            state = state + (conv to next)
            save()
            next
        }
        post(ctx, updated, silent = quiet || message.silent)
        // The conversation list shows the new message right away.
        repository.get().onEvent(ChatEvent.MessageNew(conv, payload.messageId ?: 0L, payload.senderId, null))
    }

    private suspend fun post(ctx: Context, c: ChatNotifConversation, silent: Boolean, hint: String? = null, keepBubbleOnly: Boolean = false) {
        if (!canPost()) return
        ensureChannel(ctx)
        val me = Person.Builder().setName(ctx.getString(R.string.chat_me)).setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me)
        if (c.isGroup) {
            style.setGroupConversation(true)
            style.setConversationTitle(c.title ?: ctx.getString(R.string.chat_group_fallback))
        }
        val people = HashMap<String, Person>()
        for (l in c.lines) {
            if (l.mine) {
                style.addMessage(l.text, l.timeMs, null as Person?)
                continue
            }
            val person = people.getOrPut(l.senderKey) {
                val icon = l.avatarUrl?.let { url -> withTimeoutOrNull(AVATAR_WAIT_MS) { avatars.load(url, AVATAR_PX) } }?.let { IconCompat.createWithBitmap(circle(it)) }
                Person.Builder().setName(l.senderName.ifBlank { ctx.getString(R.string.chat_unknown_person) }).setKey("u:${l.senderKey}")
                    .apply { if (icon != null) setIcon(icon) }.build()
            }
            style.addMessage(l.text, l.timeMs, person)
        }
        val id = notificationId(c.conversationId)
        val builder = NotificationCompat.Builder(ctx, if (c.isGroup) CHANNEL_GROUPS else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setSilent(silent)
            .setGroup(GROUP_KEY)
            .setNumber(c.seqs.size)
            .setContentIntent(openIntent(c.conversationId, c.messageId, c.open))
            .setDeleteIntent(receiverIntent(ChatActionReceiver.ACTION_DISMISSED, c.conversationId, slot = 1))
        hint?.let { builder.setSubText(it) }
        // A conversation (Android 11+): its long-lived shortcut, LocusId and — when allowed — a bubble.
        val cached = runCatching { repository.get().cachedConversation(c.conversationId) }.getOrNull()
        val shortcut = shortcuts.ensure(c.conversationId, cached, c.title ?: c.lines.lastOrNull { !it.mine }?.senderName, c.isGroup)
        if (shortcut != null) {
            builder.setShortcutId(shortcut.id).setLocusId(LocusIdCompat(shortcut.id))
            if (shortcuts.bubbles.value) {
                builder.setBubbleMetadata(
                    NotificationCompat.BubbleMetadata.Builder(bubbleIntent(c.conversationId), shortcut.icon)
                        .setDesiredHeightResId(R.dimen.chat_bubble_height)
                        .setAutoExpandBubble(false)
                        // Read while its bubble stays: only the bubble, no entry in the shade.
                        .setSuppressNotification(keepBubbleOnly)
                        .build(),
                )
            }
        }
        if (keepBubbleOnly) builder.setSilent(true).setNumber(0)
        if (c.replyPath != null) {
            val input = RemoteInput.Builder(ChatActionReceiver.KEY_TEXT).setLabel(ctx.getString(R.string.chat_reply_hint)).build()
            builder.addAction(
                NotificationCompat.Action.Builder(0, c.replyLabel ?: ctx.getString(R.string.chat_reply), replyIntent(c.conversationId))
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        builder.addAction(
            NotificationCompat.Action.Builder(0, ctx.getString(R.string.chat_mark_read), receiverIntent(ChatActionReceiver.ACTION_MARK_READ, c.conversationId, slot = 2))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build(),
        )
        notify(id, builder.build())
        postSummary(ctx)
    }

    private fun postSummary(ctx: Context) {
        val open = state.size
        if (open == 0) {
            NotificationManagerCompat.from(context).cancel(SUMMARY_ID)
            return
        }
        val summary = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.chat_channel))
            .setContentText(ctx.resources.getQuantityString(R.plurals.chat_summary, open, open))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(null))
            .build()
        notify(SUMMARY_ID, summary)
    }

    /** The answer from the notification was sent: shown as "Ich", then the notification goes (read). */
    suspend fun replied(conversationId: Long, text: String) {
        val ctx = localized()
        val c = mutex.withLock {
            val cur = state[conversationId] ?: return
            ChatNotifyLogic.addMine(cur, ctx.getString(R.string.chat_me), text, System.currentTimeMillis()).also {
                state = state + (conversationId to it)
                save()
            }
        }
        post(ctx, c, silent = true)
        scope.launch {
            delay(REPLIED_LINGER_MS)
            markedRead(conversationId, ACK_REPLY)
        }
    }

    /** The answer could not be sent (yet): the notification says so, quietly. */
    suspend fun replyFailed(conversationId: Long, queued: Boolean) {
        val ctx = localized()
        val c = state[conversationId] ?: return
        post(ctx, c, silent = true, hint = ctx.getString(if (queued) R.string.chat_reply_queued else R.string.chat_reply_failed))
    }

    /** "Als gelesen markieren" done (or the answer sent): acks `read` for its pushes, notification gone. */
    suspend fun markedRead(conversationId: Long, action: String = ACK_MARK_READ) {
        val gone = mutex.withLock {
            val cur = state[conversationId]
            state = state - conversationId
            save()
            cur
        }
        val ctx = localized()
        // Shown as a bubble: cancelling would close it — it stays, without an entry in the shade.
        if (gone != null && isBubble(conversationId)) {
            post(ctx, gone, silent = true, keepBubbleOnly = true)
        } else {
            NotificationManagerCompat.from(context).cancel(notificationId(conversationId))
        }
        postSummary(ctx)
        gone?.seqs?.takeIf { it.isNotEmpty() }?.let { pushRepository.get().ackChatRead(it, action) }
    }

    /** The conversation was read (opened here, read on another device). */
    fun conversationRead(conversationId: Long) {
        if (!state.containsKey(conversationId)) return
        scope.launch { markedRead(conversationId) }
    }

    /** The conversation is gone (left, removed): its notification and bubble go, nothing acked. */
    fun conversationGone(conversationId: Long) {
        scope.launch {
            mutex.withLock {
                state = state - conversationId
                save()
            }
            NotificationManagerCompat.from(context).cancel(notificationId(conversationId))
            postSummary(localized())
        }
    }

    /** The conversation's notification is shown as a bubble right now. */
    private fun isBubble(conversationId: Long): Boolean = runCatching {
        val id = notificationId(conversationId)
        nm.activeNotifications.any { it.id == id && (it.notification.flags and android.app.Notification.FLAG_BUBBLE) != 0 }
    }.getOrDefault(false)

    /** Swiped away: forget its lines (no ack — it was not read). */
    fun dismissed(conversationId: Long) {
        scope.launch {
            mutex.withLock {
                state = state - conversationId
                save()
            }
            postSummary(localized())
        }
    }

    /** Pushes [ids] read on another device or withdrawn. */
    fun gone(ids: Collection<Long>) {
        if (ids.isEmpty() || state.isEmpty()) return
        scope.launch {
            val ctx = localized()
            val changed = mutex.withLock {
                val out = mutableListOf<Pair<Long, ChatNotifConversation?>>()
                for ((id, c) in state) {
                    if (c.lines.none { it.pushId in ids }) continue
                    out += id to ChatNotifyLogic.remove(c, ids)
                }
                for ((id, c) in out) state = if (c == null) state - id else state + (id to c)
                if (out.isNotEmpty()) save()
                out
            }
            for ((id, c) in changed) {
                if (c == null) NotificationManagerCompat.from(context).cancel(notificationId(id)) else post(ctx, c, silent = true)
            }
            if (changed.isNotEmpty()) postSummary(ctx)
        }
    }

    fun conversation(conversationId: Long): ChatNotifConversation? = state[conversationId]

    // ─── intents ────────────────────────────────────────────────────

    private fun uri(conversationId: Long?, slot: Int): Uri = "gatecontrol-chat://conversation/${conversationId ?: 0}/$slot".toUri()

    private fun openIntent(conversationId: Long?, messageId: Long? = null, open: String? = null): PendingIntent {
        val intent = PushOpenActivity.chatIntent(context, conversationId, messageId, open).setData(uri(conversationId, 0))
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The bubble's content: the conversation in [ChatBubbleActivity] (bubbles need a mutable intent). */
    private fun bubbleIntent(conversationId: Long): PendingIntent {
        val intent = ChatBubbleActivity.intent(context, conversationId).setData(uri(conversationId, 4))
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private fun receiverIntent(op: String, conversationId: Long, slot: Int): PendingIntent {
        val intent = ChatActionReceiver.intent(context, op, conversationId).setData(uri(conversationId, slot))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** RemoteInput needs a mutable PendingIntent (explicit, to the non-exported receiver). */
    private fun replyIntent(conversationId: Long): PendingIntent {
        val intent = ChatActionReceiver.intent(context, ChatActionReceiver.ACTION_REPLY, conversationId).setData(uri(conversationId, 3))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            Timber.d("Chats: notification not allowed")
        }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun circle(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val out = createBitmap(side, side)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-(src.width - side) / 2f, -(src.height - side) / 2f) })
            }
        }
        Canvas(out).drawCircle(side / 2f, side / 2f, side / 2f, paint)
        return out
    }

    companion object {
        const val CHANNEL_ID = "push_chats"
        /** Messenger 1.4: group chats (own sound / vibration). */
        const val CHANNEL_GROUPS = "push_chats_groups"
        private const val GROUP_CHANNELS = "push"
        private const val GROUP_KEY = "chats"
        private const val PREFS = "chat_notifications"
        private const val KEY_STATE = "state"
        private const val AVATAR_PX = 128
        private const val AVATAR_WAIT_MS = 1_500L
        private const val REPLIED_LINGER_MS = 1_500L
        const val ACK_MARK_READ = "mark_read"
        const val ACK_REPLY = "reply"
        private val SUMMARY_ID = PushChannels.notificationId(0L, "chat:summary")

        /** One notification per conversation (the push's collapse key per chat). */
        fun notificationId(conversationId: Long): Int =
            PushChannels.notificationId(0L, "plugin:$MESSENGER_PLUGIN:chat:$conversationId")
    }
}
