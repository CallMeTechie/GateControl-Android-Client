package com.gatecontrol.android.push

import androidx.annotation.StringRes
import com.gatecontrol.android.R
import com.gatecontrol.android.notify.PushChannels
import com.gatecontrol.android.notify.model.PushPriority
import com.gatecontrol.android.notify.model.PushTopic

/** String resources of topics, priorities and channels (shade, inbox, settings). */
object PushText {

    @StringRes
    fun topicLabel(topic: String): Int? = when (topic) {
        PushTopic.SECURITY -> R.string.push_topic_security
        PushTopic.DEVICES -> R.string.push_topic_devices
        PushTopic.SERVICES -> R.string.push_topic_services
        PushTopic.SYSTEM -> R.string.push_topic_system
        PushTopic.ADMIN_NOTICE -> R.string.push_topic_admin_notice
        else -> null
    }

    @StringRes
    fun topicDescription(topic: String): Int = when (topic) {
        PushTopic.SECURITY -> R.string.push_topic_security_desc
        PushTopic.DEVICES -> R.string.push_topic_devices_desc
        PushTopic.SERVICES -> R.string.push_topic_services_desc
        PushTopic.SYSTEM -> R.string.push_topic_system_desc
        PushTopic.ADMIN_NOTICE -> R.string.push_topic_admin_notice_desc
        else -> R.string.push_topic_plugin_desc
    }

    @StringRes
    fun priorityLabel(priority: PushPriority): Int = when (priority) {
        PushPriority.CRITICAL -> R.string.push_priority_critical
        PushPriority.HIGH -> R.string.push_priority_high
        PushPriority.NORMAL -> R.string.push_priority_normal
        PushPriority.INFO -> R.string.push_priority_info
    }

    /** Name of a channel with a fixed key (critical, info, silent, connection, core topics). */
    @StringRes
    fun channelName(key: String): Int? = when (key) {
        PushChannels.KEY_CRITICAL -> R.string.push_channel_critical
        PushChannels.KEY_INFO -> R.string.push_channel_info
        PushChannels.KEY_SILENT -> R.string.push_channel_silent
        PushChannels.KEY_CONNECTION -> R.string.push_channel_connection
        else -> topicLabel(key)
    }

    /**
     * Readable fallback for a plugin topic without a label from the server:
     * `plugin:skoda:charging` → "Skoda · Charging".
     */
    fun fallbackLabel(topic: String): String {
        val parts = topic.split(':')
        if (parts.size == 3 && parts[0] == "plugin") {
            return parts[1].replaceFirstChar { it.uppercase() } + " · " + parts[2].replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        return topic
    }
}
