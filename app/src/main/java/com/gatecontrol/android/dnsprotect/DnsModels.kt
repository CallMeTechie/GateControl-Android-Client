package com.gatecontrol.android.dnsprotect

/**
 * "DNS-Schutz" (plugin gatecontrol-dns 1.0, server 1.264+) as the plugin's
 * app API answers it. The app only draws what the server says ([DnsMay]);
 * every rule is enforced by the plugin.
 */
data class DnsStatus(
    /** `active` | `paused` | `fallback` | `unfiltered` | `starting` | `off`; an unknown word is kept as it is. */
    val state: String,
    /** The asking device; null: the token has no VPN peer ("deine Geräte"). */
    val device: DnsDevice? = null,
    val profileName: String? = null,
    /** `profile.home` (plugin 1.2): the profile also holds at home (the home network's DNS filter uses it); false when missing. */
    val profileHome: Boolean = false,
    val pause: DnsPause? = null,
    /**
     * "Internet pausiert" of the family plugin (plugin 1.1, server 1.267+): only "Immer erlaubt"
     * answers for this device (without a device: the person's filtered devices). Null otherwise
     * and on older servers. Independent of [state] and of the reason in [may].
     */
    val internet: DnsInternet? = null,
    /** Since when the filter is down (ISO), with [state] `fallback`. */
    val outageSince: String? = null,
    val counters: DnsCounters = DnsCounters(),
    /** Top blocked domains of today; null: not delivered (the section is left out). */
    val top: List<DnsTopDomain>? = null,
    val may: DnsMay = DnsMay(),
    val today: List<DnsScheduleEntry> = emptyList(),
    /** Administrator or parent: `GET people` is offered. */
    val manage: Boolean = false,
    /** The server's clock (ISO) when it answered. */
    val serverTime: String? = null,
) {
    companion object {
        const val ACTIVE = "active"
        const val PAUSED = "paused"
        const val FALLBACK = "fallback"
        const val UNFILTERED = "unfiltered"
        const val STARTING = "starting"
        const val OFF = "off"
    }
}

/** [state]: `filtered` | `ownDns` | `excluded` | `disabled`. */
data class DnsDevice(
    val peerId: Int,
    val name: String,
    val state: String = "",
    val ownDns: String? = null,
)

data class DnsPause(
    /** `peer` | `user` | `all` (an administrator suspended for all devices). */
    val scope: String = "",
    val until: String? = null,
    val openEnd: Boolean = false,
    val since: String? = null,
    val byName: String? = null,
    val byMe: Boolean = false,
    val remainingSeconds: Long? = null,
    /** The chosen duration (`until − since`, with "+30 Min." the whole of it; plugin 1.1); null with [openEnd] or from an older plugin. */
    val minutes: Int? = null,
)

/** "Internet pausiert" (Familie) until [until] (ISO). */
data class DnsInternet(
    val until: String? = null,
    val remainingSeconds: Long? = null,
)

/** [scope]: `device` | `person` | `server`. */
data class DnsCounters(
    val scope: String = "",
    val queries: Long = 0,
    val blocked: Long = 0,
    val percent: Double = 0.0,
)

data class DnsTopDomain(val domain: String, val count: Long)

data class DnsMay(
    val pause: Boolean = false,
    val resume: Boolean = false,
    val extend: Boolean = false,
    /** Why [pause] is not offered: `admins_only` | `profile` | `not_filtered` | `no_device` | `fallback` | `off` | `paused_for_all` | `internet_paused` (plugin 1.1). */
    val reason: String? = null,
    /** Allowed minutes, a subset of 5/30/60/240. */
    val durations: List<Int> = emptyList(),
    val maxMinutes: Int = 0,
    val forever: Boolean = false,
)

/** A time of the profile that holds today; [block] `all` or `categories`. */
data class DnsScheduleEntry(
    val from: String,
    val to: String,
    val block: String = "",
    val categories: List<String> = emptyList(),
)

/** `GET people`: whom the viewer (administrator, parent) may suspend for. */
data class DnsPeople(
    /** "Alle VPN-Geräte" — administrators only. */
    val all: DnsAll? = null,
    val people: List<DnsPerson> = emptyList(),
)

data class DnsAll(val may: DnsMay, val pause: DnsPause? = null)

data class DnsPerson(
    val userId: Int,
    val name: String,
    val self: Boolean = false,
    val may: DnsMay = DnsMay(),
    val devices: List<DnsPersonDevice> = emptyList(),
    /**
     * The person's suspension (plugin 1.1: `people[].pause`), else the common one when all their
     * devices are suspended; from an older plugin derived from the devices.
     */
    val pause: DnsPause? = null,
    /** "Internet pausiert" (Familie) for this person (plugin 1.1). */
    val internet: DnsInternet? = null,
)

data class DnsPersonDevice(
    val peerId: Int,
    val name: String,
    val state: String = "",
    val suspended: Boolean = false,
    val pause: DnsPause? = null,
    val may: DnsMay = DnsMay(),
    /** "Internet pausiert" (Familie) for this device until (ISO); null: not paused (plugin 1.1). */
    val internetPaused: String? = null,
)

/** Whom a change is for: nothing = the asking device. */
sealed interface DnsTarget {
    data object Own : DnsTarget
    data class Peer(val peerId: Int) : DnsTarget
    data class User(val userId: Int) : DnsTarget
    data object All : DnsTarget
}

/** How long: [Minutes], until switched back on, or [Extend] the running suspension ("+30 Min."). */
sealed interface DnsDuration {
    data class Minutes(val minutes: Int) : DnsDuration
    data object Forever : DnsDuration
    /** From the end of the running suspension on (`{ extend: minutes }`). */
    data class Extend(val minutes: Int) : DnsDuration
}

/** Outcome of a call to the plugin. */
sealed interface DnsResult<out T> {
    data class Ok<T>(val value: T) : DnsResult<T>

    /** The server refused: [code] from `{ ok: false, code }` or `http_<status>`; [message] its ready sentence. */
    data class Failed(val code: String, val http: Int, val message: String? = null, val reason: String? = null) : DnsResult<Nothing>

    /** No answer (network, VPN, timeout). */
    data object Offline : DnsResult<Nothing>

    /** The server reports no DNS protection (or the app is not set up). */
    data object Unavailable : DnsResult<Nothing>
}
