package com.gatecontrol.android.familie.internet

import com.gatecontrol.android.familie.MemberFace
import com.gatecontrol.android.familie.presence.PresenceState

/*
 * Familie „Internet zu Hause“ (plugin 1.18, in the apps from 1.20; `GET
 * internet`, `POST internet/<childId>/pause · resume · extend`): parents
 * pause the internet of a minor child's devices in the home network or let
 * the profile's blocked times start later today ("Heute länger"). The DNS
 * filter of a gateway does the work; away from home nothing of it applies.
 * A child sees its own state read only.
 */

/** Who set a pause or an extra time; [ruleId] when a rule did it in that person's name ([ruleTitle] may be empty). */
data class InternetBy(
    val userId: Int?,
    val name: String,
    val at: String?,
    val ruleId: Int? = null,
    val ruleTitle: String = "",
) {
    val byRule: Boolean get() = ruleId != null
}

/** The next blocked time of the child's DNS profile (set by the admin, read only). */
data class InternetSchedule(
    val from: String?,
    val to: String?,
    /** `block: "all"`: everything; else only [categories]. */
    val all: Boolean,
    /** Of `social`, `games`, `video` (unknown ones are dropped). */
    val categories: List<String>,
    /** It runs right now. */
    val active: Boolean,
) {
    companion object {
        const val SOCIAL = "social"
        const val GAMES = "games"
        const val VIDEO = "video"
        val CATEGORIES = listOf(SOCIAL, GAMES, VIDEO)
    }
}

/** Where the child is; [source] `wifi` (seen by the gateway) | `app` | null. */
data class InternetPresence(val state: PresenceState, val since: String?, val source: String?) {
    companion object {
        const val SOURCE_WIFI = "wifi"
        const val SOURCE_APP = "app"
    }
}

/** One child of `GET internet` (also the answer of a change). */
data class InternetChild(
    val userId: Int,
    val name: String,
    /** `#RRGGBB` or null. */
    val color: String? = null,
    val face: MemberFace? = null,
    /** null: the presence module is off (or a plugin before 1.20). */
    val presence: InternetPresence? = null,
    /** ISO end of a pause, else null. It runs while it lies after the server's time. */
    val paused: String? = null,
    val pausedBy: InternetBy? = null,
    /** ISO end of "Heute länger", else null. */
    val extended: String? = null,
    val extendedBy: InternetBy? = null,
    val nextSchedule: InternetSchedule? = null,
    /** Name of the DNS profile. */
    val profile: String? = null,
    /** How many devices of the child the home network knows — a number only, never a list. */
    val devices: Int = 0,
    /** A gateway with a DNS filter knows devices of the child; false: a pause would do nothing. */
    val filtered: Boolean = false,
    /** `invalid` | `unavailable` | `error`: the state can't be read right now. */
    val error: String? = null,
)

/** `GET internet`. [manage] false: read only (a child). */
data class InternetPayload(
    val available: Boolean,
    val manage: Boolean,
    val children: List<InternetChild>,
    val serverTime: String? = null,
    /** IANA zone of the family (times of day are meant in it). */
    val timezone: String? = null,
) {
    fun child(userId: Int?): InternetChild? = children.firstOrNull { it.userId == userId }

    /** The card and the page have something to show. */
    val visible: Boolean get() = available && children.isNotEmpty()
}

/** A payload with the device time it arrived at ([fetchedAtMs]), for "now" in the server's clock. */
data class InternetSnapshot(val payload: InternetPayload, val fetchedAtMs: Long) {
    /** The server's time at device time [deviceNowMs]; the device's own without a server time. */
    fun nowMs(deviceNowMs: Long): Long {
        val server = InternetLogic.epoch(payload.serverTime) ?: return deviceNowMs
        return server + (deviceNowMs - fetchedAtMs).coerceAtLeast(0)
    }

    /** [child] replaces the one with its id (the answer of a change). */
    fun with(child: InternetChild): InternetSnapshot =
        copy(payload = payload.copy(children = payload.children.map { if (it.userId == child.userId) child else it }))
}

/** What a child's card shows, in the order the portal checks it. */
enum class InternetState {
    /** A pause runs. */
    PAUSED,
    /** "Heute länger" runs. */
    EXTENDED,
    /** The state can't be read. */
    ERROR,
    /** Filtered, nothing special. */
    ON,
    /** No gateway with a DNS filter knows devices of the child. */
    UNFILTERED,
}

/** Body of `POST internet/<childId>/pause`: exactly one of the three. */
sealed interface PauseRequest {
    /** From now, 1…10080 minutes. */
    data class Minutes(val minutes: Int) : PauseRequest

    /** Until the next such time of day (`HH:MM`, the family's time zone). */
    data class Until(val time: String) : PauseRequest

    /** A running pause gets longer ("+30 Min."), 1…1440 minutes. */
    data class Add(val minutes: Int) : PauseRequest
}

/** One of the person's own devices in the home Wi-Fi (`GET presence/wifi` → `devices[]`); never an address. */
data class WifiDevice(
    val id: Int,
    val name: String,
    val lastSeen: String?,
    /** This device counts for "zu Hause". */
    val counts: Boolean,
    val gateway: String?,
)

/**
 * `GET presence/wifi`: my own switch "WLAN-Erkennung verwenden" ([consent])
 * and my devices. [available] false: the server has no Wi-Fi detection.
 */
data class WifiPresence(
    val available: Boolean,
    val consent: Boolean = false,
    val since: String? = null,
    val devices: List<WifiDevice> = emptyList(),
)
