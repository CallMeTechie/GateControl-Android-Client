package com.gatecontrol.android.chat

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.IconCompat
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.familie.familieLocalized
import com.gatecontrol.android.profile.AvatarLoader
import com.gatecontrol.android.push.PushOpenActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A conversation's shortcut as a notification needs it (id and icon for the bubble). */
data class ChatShortcutRef(val id: String, val icon: IconCompat)

/** Pure parts of the conversation shortcuts. Unit-tested. */
object ChatShortcutLogic {
    /** Ids of the chat shortcuts; quick actions and Familie keep theirs. */
    const val PREFIX = "chat:"

    /** Recent conversations with a dynamic shortcut (launcher, share sheet). */
    const val MAX_RECENT = 4

    /** Category of the sharing shortcuts (res/xml/shortcuts.xml `share-target`). */
    const val SHARE_CATEGORY = "com.gatecontrol.android.chat.SHARE_TARGET"

    /** ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION. */
    const val CONVERSATION_CATEGORY = "android.shortcut.conversation"

    /** Chat shortcuts come after the quick actions (lower rank = shown first). */
    const val RANK_BASE = 20

    fun id(conversationId: Long): String = "$PREFIX$conversationId"

    /** The conversation of a shortcut id (`chat:<id>`), null for other shortcuts. */
    fun conversationOf(shortcutId: String?): Long? =
        shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()?.takeIf { it > 0 }

    /** The conversations that get a dynamic shortcut: newest activity first, at most [max]. */
    fun recent(conversations: List<ChatConversation>, max: Int = MAX_RECENT): List<ChatConversation> =
        if (max <= 0) emptyList() else conversations.sortedByDescending { it.activityAt }.take(max)

    /** Something can be shared into [c] (a sharing shortcut). */
    fun shareable(c: ChatConversation): Boolean = c.can.send && !c.readonly

    /** Chat shortcuts among [existingIds] whose conversation is not in [keep]. */
    fun stale(existingIds: Collection<String>, keep: Set<Long>): List<String> =
        existingIds.filter { id -> conversationOf(id)?.let { it !in keep } == true }

    /** What a shortcut shows: republished only when this changes. */
    fun signature(c: ChatConversation, title: String): String =
        listOf(c.id, title, c.avatarMediaId, c.peer?.avatarUrl, c.peer?.colorHex, shareable(c)).joinToString("|")

    /**
     * How many chat shortcuts fit beside [others] dynamic shortcuts of other
     * features, keeping at least [reserved] places for them.
     */
    fun room(maxPerActivity: Int, others: Int, reserved: Int, max: Int = MAX_RECENT): Int =
        minOf(max, maxPerActivity - maxOf(others, reserved)).coerceAtLeast(0)
}

/**
 * Conversation shortcuts (Android 11+ "Unterhaltungen"): a long-lived
 * dynamic shortcut per recent conversation with its person(s), picture
 * and LocusId — on the launcher, as sharing shortcuts in the share sheet
 * ("Teilen → Chat" straight into that chat) and as the shortcut of the
 * chat notifications (conversation section, bubbles). Removed when a chat
 * is left or removed, and all of them when the server or token changes.
 * The person can switch shortcuts and bubbles off for this device.
 */
@Singleton
class ChatShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val avatars: AvatarLoader,
    private val media: ChatMediaStore,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    /** Shortcuts on for this device (launcher, share sheet, conversation notifications). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _bubbles = MutableStateFlow(prefs.getBoolean(KEY_BUBBLES, true))
    /** Chat notifications may open as bubbles (the system setting still decides). */
    val bubbles: StateFlow<Boolean> = _bubbles.asStateFlow()

    /** Shortcut id → signature of what is published (survives a restart). */
    private var published: Map<String, String> = prefs.getStringSet(KEY_PUBLISHED, emptySet()).orEmpty()
        .mapNotNull { e -> e.split('\u0001', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    private fun savePublished() {
        prefs.edit { putStringSet(KEY_PUBLISHED, published.map { (k, v) -> "$k\u0001$v" }.toSet()) }
    }

    suspend fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, on) }
        _enabled.value = on
        if (!on) removeAll()
    }

    fun setBubbles(on: Boolean) {
        prefs.edit { putBoolean(KEY_BUBBLES, on) }
        _bubbles.value = on
    }

    /** The recent conversations' shortcuts; the others' dynamic ones go (pinned and cached ones stay). */
    suspend fun publish(conversations: List<ChatConversation>) {
        if (!_enabled.value) return
        mutex.withLock {
            runCatching {
                val existing = ShortcutManagerCompat.getDynamicShortcuts(context)
                val others = existing.count { ChatShortcutLogic.conversationOf(it.id) == null }
                val room = ChatShortcutLogic.room(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context), others, RESERVED_FOR_OTHERS)
                val recent = ChatShortcutLogic.recent(conversations, room)
                val stale = ChatShortcutLogic.stale(existing.map { it.id }, recent.map { it.id }.toSet())
                if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
                val dynamic = existing.map { it.id }.toSet() - stale.toSet()
                val ctx = familieLocalized(context, settingsRepository)
                val fresh = recent.mapIndexedNotNull { index, c ->
                    val title = titleOf(ctx, c)
                    val id = ChatShortcutLogic.id(c.id)
                    val sig = ChatShortcutLogic.signature(c, title)
                    if (id in dynamic && published[id] == sig) null else Triple(build(ctx, c, title, ChatShortcutLogic.RANK_BASE + index).first, id, sig)
                }
                if (fresh.isNotEmpty()) {
                    // A chat left earlier and joined again: its pinned shortcut works again.
                    ShortcutManagerCompat.enableShortcuts(context, fresh.map { it.first })
                    ShortcutManagerCompat.addDynamicShortcuts(context, fresh.map { it.first })
                    published = published + fresh.associate { it.second to it.third }
                    savePublished()
                }
            }.onFailure { Timber.w("Chats: shortcuts not published (%s)", it.javaClass.simpleName) }
        }
    }

    /**
     * The shortcut of the conversation a notification is about to show
     * ([c] the cached conversation, else what the push said). Null when
     * shortcuts are off or the system refused.
     */
    suspend fun ensure(conversationId: Long, c: ChatConversation?, fallbackTitle: String?, isGroup: Boolean): ChatShortcutRef? {
        if (!_enabled.value) return null
        return mutex.withLock {
            runCatching {
                val ctx = familieLocalized(context, settingsRepository)
                val id = ChatShortcutLogic.id(conversationId)
                if (c != null) {
                    val title = titleOf(ctx, c)
                    val (info, icon) = build(ctx, c, title, ChatShortcutLogic.RANK_BASE + ChatShortcutLogic.MAX_RECENT)
                    val sig = ChatShortcutLogic.signature(c, title)
                    ShortcutManagerCompat.enableShortcuts(context, listOf(info))
                    ShortcutManagerCompat.pushDynamicShortcut(context, info)
                    published = published + (id to sig)
                    savePublished()
                    ChatShortcutRef(id, icon)
                } else {
                    val title = fallbackTitle?.takeIf { it.isNotBlank() }
                        ?: ctx.getString(if (isGroup) R.string.chat_group_fallback else R.string.chat_unknown_person)
                    val icon = IconCompat.createWithAdaptiveBitmap(initialsBitmap(ChatPerson.initialsOf(title), null))
                    val info = baseBuilder(conversationId, title, icon, ChatShortcutLogic.RANK_BASE + ChatShortcutLogic.MAX_RECENT, share = false).build()
                    ShortcutManagerCompat.pushDynamicShortcut(context, info)
                    ChatShortcutRef(id, icon)
                }
            }.onFailure { Timber.w("Chats: shortcut not set (%s)", it.javaClass.simpleName) }.getOrNull()
        }
    }

    /** The conversation was opened in the app: ranks its shortcut for the launcher and share sheet. */
    fun reportUsed(conversationId: Long) {
        if (!_enabled.value) return
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, ChatShortcutLogic.id(conversationId)) }
    }

    /** Conversations left or removed: their shortcuts go (pinned ones are disabled). */
    suspend fun remove(conversationIds: Collection<Long>) {
        if (conversationIds.isEmpty()) return
        drop(conversationIds.map { ChatShortcutLogic.id(it) })
    }

    /** Another server or token, chats gone or switched off: every chat shortcut goes. */
    suspend fun removeAll() {
        val ids = runCatching {
            val flags = ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_CACHED
            ShortcutManagerCompat.getShortcuts(context, flags).map { it.id }
        }.getOrDefault(emptyList()).filter { ChatShortcutLogic.conversationOf(it) != null }
        drop((ids + published.keys).distinct())
    }

    private suspend fun drop(ids: List<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            runCatching {
                ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
                ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
                val ctx = familieLocalized(context, settingsRepository)
                ShortcutManagerCompat.disableShortcuts(context, ids, ctx.getString(R.string.chat_shortcut_gone))
            }.onFailure { Timber.w("Chats: shortcuts not removed (%s)", it.javaClass.simpleName) }
            published = published - ids.toSet()
            savePublished()
        }
    }

    // ─── building ───────────────────────────────────────────────────

    private fun titleOf(ctx: Context, c: ChatConversation): String = when {
        !c.title.isNullOrBlank() -> c.title
        c.isGroup -> ctx.getString(R.string.chat_group_fallback)
        c.peer?.gone == true -> ctx.getString(R.string.chat_deleted_account)
        else -> ctx.getString(R.string.chat_unknown_person)
    }

    private fun baseBuilder(conversationId: Long, title: String, icon: IconCompat, rank: Int, share: Boolean): ShortcutInfoCompat.Builder {
        val id = ChatShortcutLogic.id(conversationId)
        val categories = buildSet {
            add(ChatShortcutLogic.CONVERSATION_CATEGORY)
            if (share) add(ChatShortcutLogic.SHARE_CATEGORY)
        }
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(title.take(SHORT_LABEL_MAX))
            .setLongLabel(title.take(LONG_LABEL_MAX))
            .setIcon(icon)
            .setIntent(PushOpenActivity.chatIntent(context, conversationId).setAction(Intent.ACTION_VIEW))
            .setLongLived(true)
            .setLocusId(LocusIdCompat(id))
            .setCategories(categories)
            .setRank(rank)
    }

    private suspend fun build(ctx: Context, c: ChatConversation, title: String, rank: Int): Pair<ShortcutInfoCompat, IconCompat> {
        val picture = withTimeoutOrNull(ICON_WAIT_MS) {
            if (c.isGroup) c.avatarMediaId?.let { media.bitmap(it, ICON_PX) } else c.peer?.avatarUrl?.let { avatars.load(it, ICON_PX) }
        }
        val bitmap = picture?.let { adaptive(it) }
            ?: initialsBitmap(c.peer?.initials ?: ChatPerson.initialsOf(title), if (c.isGroup) null else c.peer?.colorHex)
        val icon = IconCompat.createWithAdaptiveBitmap(bitmap)
        val builder = baseBuilder(c.id, title, icon, rank, share = ChatShortcutLogic.shareable(c))
        if (!c.isGroup) {
            val peer = c.peer
            val person = Person.Builder()
                .setName(title)
                .setKey(peer?.let { "u:${it.userId}" } ?: "c:${c.id}")
                .setIcon(icon)
                .build()
            builder.setPerson(person)
        } else {
            builder.setPersons(c.members.take(MAX_PERSONS).map { m -> Person.Builder().setName(m.name ?: ctx.getString(R.string.chat_deleted_account)).setKey("u:${m.userId}").build() }.toTypedArray())
        }
        return builder.build() to icon
    }

    /** A picture filling the adaptive icon (108 dp; the launcher masks the middle). */
    private fun adaptive(src: Bitmap): Bitmap {
        val out = createBitmap(ADAPTIVE_PX, ADAPTIVE_PX)
        val side = minOf(src.width, src.height)
        val from = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
        Canvas(out).drawBitmap(src, from, RectF(0f, 0f, ADAPTIVE_PX.toFloat(), ADAPTIVE_PX.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Initials on the person's favourite colour (groups: the accent). */
    private fun initialsBitmap(initials: String, colorHex: String?): Bitmap {
        val out = createBitmap(ADAPTIVE_PX, ADAPTIVE_PX)
        val canvas = Canvas(out)
        val bg = colorHex?.let { runCatching { it.toColorInt() }.getOrNull() } ?: ACCENT
        canvas.drawColor(bg)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (luminance(bg) > 0.6) DARK_TEXT else Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = ADAPTIVE_PX * 0.26f
        }
        val y = ADAPTIVE_PX / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials.take(2).ifBlank { "?" }, ADAPTIVE_PX / 2f, y, paint)
        return out
    }

    private fun luminance(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    companion object {
        private const val PREFS = "chat_shortcuts"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BUBBLES = "bubbles"
        private const val KEY_PUBLISHED = "published"
        private const val SHORT_LABEL_MAX = 25
        private const val LONG_LABEL_MAX = 80
        private const val MAX_PERSONS = 8
        private const val ICON_PX = 216
        private const val ADAPTIVE_PX = 216
        private const val ICON_WAIT_MS = 2_000L
        /** Quick actions (4) and the Familie emergency card keep their places. */
        private const val RESERVED_FOR_OTHERS = 5
        private const val ACCENT = 0xFF3DDC97.toInt()
        private const val DARK_TEXT = 0xFF03261A.toInt()
    }
}

/** The system's own bubble setting (Android 12+: per app, and on/off for the whole device). */
object ChatSystemBubbles {
    /** False when the system shows no bubbles of this app; null when unknown. */
    fun allowed(context: Context): Boolean? = runCatching {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.areBubblesEnabled() && nm.bubblePreference != NotificationManager.BUBBLE_PREFERENCE_NONE
    }.getOrNull()

    /** The app's bubble settings of the system. */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
