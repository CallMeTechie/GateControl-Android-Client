package com.gatecontrol.android.network

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

// Device ↔ server contract of the notification center
// (gatecontrol docs/feature-notification-center.md, "Vertrag Gerät ↔ Server").
// Every field is nullable: Gson does not apply Kotlin defaults, and the
// payload is validated in core/notify before anything is shown.

/** One `notification` event / inbox item. */
data class PushNotificationDto(
    val seq: Long? = null,
    val id: Long? = null,
    @SerializedName("event_id") val eventId: String? = null,
    val topic: String? = null,
    val priority: String? = null,
    val title: String? = null,
    val body: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("collapse_key") val collapseKey: String? = null,
    val silent: Boolean? = null,
    /** Free-form JSON (≤ 4 KB): `route`, `actions`, context. */
    val data: JsonObject? = null,
    /** Inbox only: delivered | read | dismissed. */
    val state: String? = null,
    /**
     * Picture of the message (core 1.169+): a relative path on the own
     * server (`/notify-media/<id>.<ext>`), null without one or once expired.
     */
    @SerializedName("imageUrl") val imageUrl: String? = null,
)

data class PushTopicDto(
    val id: String? = null,
    val label: String? = null,
)

/** `event: hello` right after the stream opened. */
data class PushHelloDto(
    @SerializedName("server_time") val serverTime: String? = null,
    @SerializedName("keepalive_s") val keepaliveS: Int? = null,
    @SerializedName("retention_h") val retentionH: Int? = null,
    val via: String? = null,
    val unread: Int? = null,
    val topics: List<PushTopicDto>? = null,
)

/** `event: read` / `event: revoke`: notification ids (not seqs). */
data class PushIdsDto(
    val ids: List<Long>? = null,
)

data class PushAckRequest(
    val seqs: List<Long>,
    /** delivered | read | dismissed */
    val state: String,
    val action: String? = null,
)

data class PushPrefsRequest(
    val enabled: Boolean,
    /** always | vpn_only */
    val mode: String,
    @SerializedName("muted_topics") val mutedTopics: List<String>,
    /** Battery optimisation is active for the app (Android). */
    val restricted: Boolean,
)

data class PushInboxResponse(
    val ok: Boolean = false,
    val items: List<PushNotificationDto>? = null,
    val unread: Int? = null,
)

data class PushTestResponse(
    val ok: Boolean = false,
    val seq: Long? = null,
    val error: String? = null,
)
