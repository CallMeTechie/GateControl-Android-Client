package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushPriority
import com.gatecontrol.android.notify.model.PushTopic

/** Android importance of a channel (mirrors NotificationManager.IMPORTANCE_*). */
enum class ChannelImportance { MIN, LOW, DEFAULT, HIGH }

/** What a notification channel is for; the app turns it into a NotificationChannel. */
data class ChannelSpec(
    val id: String,
    val importance: ChannelImportance,
    /** Core topic or special channel (labels from the app's resources); null for plugin topics. */
    val key: String?,
    /** Label from the server (`hello.topics`) for plugin topics. */
    val label: String? = null,
    /** false: the channel has no sound and no vibration. */
    val sound: Boolean = true,
)

/**
 * Channel plan and notification ids.
 *
 * * `critical` — critical messages of any topic (high importance, sound).
 * * one channel per topic: `security` (high), `devices`, `services`,
 *   `system`, `admin_notice` and every plugin topic from `hello` (default) —
 *   so sound and vibration can be set per topic in Android.
 * * `info` — info messages: default importance but without sound and
 *   vibration, so they still show on the lock screen (a low channel counts
 *   as "silent", which Pixel and others hide there). Replaces the old low
 *   channel `push_info`, whose importance the app can no longer raise.
 * * `silent` — `silent:true` (quiet hours, all-clear): low, posted silently.
 * * `connection` — the one ongoing notification of the push service (min).
 */
object PushChannels {
    const val CRITICAL = "push_critical"
    const val INFO = "push_info_quiet"

    /** Earlier info channel (low importance); deleted when the channels are set up. */
    const val LEGACY_INFO = "push_info"
    const val SILENT = "push_silent"
    const val CONNECTION = "push_connection"
    private const val TOPIC_PREFIX = "push_topic_"

    const val KEY_CRITICAL = "critical"
    const val KEY_INFO = "info"
    const val KEY_SILENT = "silent"
    const val KEY_CONNECTION = "connection"

    private val INFO_SPEC = ChannelSpec(INFO, ChannelImportance.DEFAULT, KEY_INFO, sound = false)
    private val SILENT_SPEC = ChannelSpec(SILENT, ChannelImportance.LOW, KEY_SILENT, sound = false)

    /** Channels that always exist. */
    fun fixed(): List<ChannelSpec> = listOf(
        ChannelSpec(CRITICAL, ChannelImportance.HIGH, KEY_CRITICAL),
        INFO_SPEC,
        SILENT_SPEC,
        ChannelSpec(CONNECTION, ChannelImportance.MIN, KEY_CONNECTION, sound = false),
    ) + PushTopic.CORE.map { topicChannel(it, null) }

    fun topicChannelId(topic: String): String = TOPIC_PREFIX + topic

    fun topicChannel(topic: String, label: String?): ChannelSpec = ChannelSpec(
        id = topicChannelId(topic),
        importance = if (topic == PushTopic.SECURITY) ChannelImportance.HIGH else ChannelImportance.DEFAULT,
        key = topic.takeIf { it in PushTopic.CORE },
        label = label,
    )

    /** Channels for the topics of `hello` (plugin topics are created on the fly). */
    fun forTopics(topics: List<PushTopic>): List<ChannelSpec> = topics.map { topicChannel(it.id, it.label) }

    /** Priority → channel. */
    fun channelFor(message: PushMessage): ChannelSpec = when {
        message.silent -> SILENT_SPEC
        message.priorityLevel == PushPriority.CRITICAL -> ChannelSpec(CRITICAL, ChannelImportance.HIGH, KEY_CRITICAL)
        message.priorityLevel == PushPriority.INFO -> INFO_SPEC
        else -> topicChannel(message.topic, null)
    }

    /**
     * Android notification id: the same `collapse_key` replaces the shown
     * notification, otherwise one per notification id. Always ≥ 2^30, so it
     * never collides with the fixed ids of the foreground services.
     */
    fun notificationId(message: PushMessage): Int = notificationId(message.id, message.collapseKey)

    fun notificationId(id: Long, collapseKey: String?): Int {
        val key = if (collapseKey != null) "ck:$collapseKey" else "id:$id"
        return (key.hashCode() and 0x3fffffff) or 0x40000000
    }
}

/** Posts and removes message notifications (implemented by the app). */
interface PushPresenter {
    /** Creates or updates the channels for these topics (labels from the server). */
    suspend fun ensureTopicChannels(topics: List<PushTopic>)

    /**
     * Shows [message] under [notificationId] in [channel]. [quiet] posts it
     * without sound and banner (local mute); a muted message still lands in
     * the inbox, it is just not posted.
     */
    suspend fun show(message: PushMessage, notificationId: Int, channel: ChannelSpec, quiet: Boolean)

    fun cancel(notificationId: Int)

    /**
     * A message this device sees for the first time (not a replay of one it
     * already has), whether or not it is shown — for app features that act
     * on a message by themselves (e.g. answering a locate request).
     */
    suspend fun onNew(message: PushMessage) {}
}
