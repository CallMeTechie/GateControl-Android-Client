package com.gatecontrol.android.door

/**
 * Kameras im Heimnetz (plugin 1.6.0 on GateControl 1.240.0, `server/home.js`): ONVIF/RTSP
 * cameras behind a GateControl gateway. The server lists only the cameras this person
 * "sieht" (column on the plugin's rights page). Media addresses are paths on the own server
 * (`/camera-media/<token>/<file>`, no login: the token is the permission, valid 10 min,
 * thumbnails and clips 1 h). See docs/DOOR.md "Kameras im Heimnetz".
 */

/** What a camera records (`settings`). */
data class HomeCameraSettings(
    /** motion | away | never */
    val record: String,
    /** Kinds that make a clip: person, vehicle, animal, motion. */
    val events: List<String>,
    val retentionDays: Int?,
    /** main | sub */
    val stream: String,
    val audio: Boolean,
)

/** One camera of `GET cameras/home`. */
data class HomeCamera(
    /** `c` + 15 hex digits. */
    val id: String,
    val name: String,
    val gateway: String?,
    val model: String?,
    /** ok | unreachable | auth_failed | not_allowed | no_stream | error | … */
    val state: String?,
    val online: Boolean,
    val codec: String?,
    val settings: HomeCameraSettings,
    val snapshotAt: String?,
    val lastEventAt: String?,
    /** Resolution of the stream (plugin 1.7.0, the gateway's report); null unknown. */
    val width: Int? = null,
    val height: Int? = null,
    /** Plugin 1.7.0: the app offers "Ton" (sound switched on and the stream not known to have none). */
    val audio: Boolean = false,
    /** Plugin 1.7.0: the stream has a sound track; null unknown (older GateControl / gateway). */
    val hasAudio: Boolean? = null,
    /** The administrator's push switch of the camera ("Push mit Standbild bei Personen"). */
    val push: Boolean = true,
    /**
     * Plugin 1.7.0: this person's own push "Personen an der Kamera" for this camera
     * (`prefs.push`); null: an older plugin without the switch (no row in the app).
     */
    val myPush: Boolean? = null,
)

/** Answer of `GET cameras/home`. [supported] false: GateControl before 1.240.0 (nothing to show). */
data class HomeCameraList(
    val supported: Boolean,
    val minGatecontrol: String?,
    /** Seconds between two stills without VPN. */
    val every: Int,
    val cameras: List<HomeCamera>,
)

/**
 * Answer of `GET cameras/home/<id>/live`: [MODE_LIVE] (in the VPN or the server's LAN) with
 * the HLS list, else [MODE_SNAPSHOT] (`reason: vpn_required`) — a new still every [every] s.
 * Paths are relative to the own server.
 */
data class HomeLive(
    val mode: String,
    /** vpn | lan | internet */
    val via: String?,
    /** The gateway's ring is already running (else the first segments take a moment). */
    val ready: Boolean,
    val hlsPath: String?,
    val snapshotPath: String?,
    val audio: Boolean,
    val every: Int,
    val expiresAt: String?,
    val reason: String?,
) {
    val isLive: Boolean get() = mode == MODE_LIVE && hlsPath != null

    companion object {
        const val MODE_LIVE = "live"
        const val MODE_SNAPSHOT = "snapshot"
    }
}

/** One event of `GET cameras/home/<id>/clips` (newest first). */
data class HomeClip(
    /** `e` + 15 hex digits. */
    val id: String,
    /** The most important kind: person > vehicle > animal > motion. */
    val kind: String,
    val kinds: List<String>,
    val startedAt: String?,
    val endedAt: String?,
    /** Everybody was away (presence of the family); null = unknown. */
    val nobodyHome: Boolean?,
    /** Length of the clip, null without one (`record: never`, no service). */
    val ms: Long?,
    val thumbPath: String?,
    val clipPath: String?,
)

/** Answer of `GET cameras/home/<id>/clips`. */
data class HomeClips(val day: String?, val clips: List<HomeClip>)

/** Kinds of a camera event. */
object HomeCameraKind {
    const val PERSON = "person"
    const val VEHICLE = "vehicle"
    const val ANIMAL = "animal"
    const val MOTION = "motion"
    val ALL = listOf(PERSON, VEHICLE, ANIMAL, MOTION)
}
