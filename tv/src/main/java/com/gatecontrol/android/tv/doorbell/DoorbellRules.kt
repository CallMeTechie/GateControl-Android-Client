package com.gatecontrol.android.tv.doorbell

import com.gatecontrol.android.door.DoorAction
import com.gatecontrol.android.door.DoorLock
import com.gatecontrol.android.door.DoorLogic
import com.gatecontrol.android.door.DoorOverview
import com.gatecontrol.android.door.DoorPushInfo
import com.gatecontrol.android.door.HomeCamera

/** How the TV shows a ring (Einstellungen › Einblendungen › "Klingel einblenden"). */
enum class DoorbellMode(val wire: String) {
    /** A window at the bottom right; over another app only with "Über anderen Apps einblenden", else a notice. */
    WINDOW("window"),

    /** Always on screen, also over a film in another app (interrupts it). */
    FULLSCREEN("fullscreen"),

    /** Only the system notice of Fire OS. */
    NOTICE("notice");

    companion object {
        fun fromWire(raw: String?): DoorbellMode = entries.firstOrNull { it.wire == raw } ?: WINDOW
    }
}

/** The doorbell's settings. Quiet hours as minutes of the day, [quietFrom] may be after [quietTo] (over midnight). */
data class DoorbellSettings(
    val mode: DoorbellMode = DoorbellMode.WINDOW,
    val quiet: Boolean = false,
    val quietFrom: Int = 22 * 60,
    val quietTo: Int = 7 * 60,
    /** Also on motion at the doorbell camera (default: only when it rings). */
    val motion: Boolean = false,
    /** Home camera (`c` + 15 hex) whose live picture the overlay shows; null: the doorbell's own snapshot. */
    val cameraId: String? = null,
)

/** What a door push does on the TV. */
enum class DoorbellShow {
    /** Not a ring for the TV (other door pushes, motion while off): nothing beyond the usual notification. */
    NONE,

    /** Quiet hours: no overlay, the notification stays. */
    QUIET,

    /** The window, the app is in front or the right to draw over other apps is there. */
    WINDOW,

    /** Over everything, interrupting another app. */
    FULLSCREEN,

    /** Only the notice: another app is in front without the overlay right ("nur Hinweis" or mode window). */
    NOTICE,
}

/** Pure, unit-tested decisions of the doorbell overlay. */
object DoorbellRules {
    const val MINUTES_PER_DAY = 24 * 60

    /** [minute] (0..1439) lies in the quiet hours [from]..[to] (end excluded; over midnight when from > to). */
    fun inQuietHours(minute: Int, from: Int, to: Int): Boolean {
        if (from == to) return false
        return if (from < to) minute in from until to else minute >= from || minute < to
    }

    fun decide(
        info: DoorPushInfo,
        settings: DoorbellSettings,
        minuteOfDay: Int,
        appInFront: Boolean,
        canDrawOverlays: Boolean,
    ): DoorbellShow {
        val ring = info.type == DoorPushInfo.DING || (settings.motion && info.type == DoorPushInfo.MOTION)
        if (!ring) return DoorbellShow.NONE
        if (settings.quiet && inQuietHours(minuteOfDay, settings.quietFrom, settings.quietTo)) return DoorbellShow.QUIET
        return when (settings.mode) {
            DoorbellMode.NOTICE -> DoorbellShow.NOTICE
            DoorbellMode.FULLSCREEN -> DoorbellShow.FULLSCREEN
            DoorbellMode.WINDOW -> if (appInFront || canDrawOverlays) DoorbellShow.WINDOW else DoorbellShow.NOTICE
        }
    }

    /** The home camera of the live picture: the one chosen in the settings while this person still sees it. */
    fun camera(settings: DoorbellSettings, cameras: List<HomeCamera>): HomeCamera? =
        settings.cameraId?.let { id -> cameras.firstOrNull { it.id == id } }

    /**
     * The lock the overlay's "Tür öffnen" acts on: the one the ringing camera belongs to (`lockId`), else the only
     * lock; only when this person may open it right now. Null: no button.
     */
    fun openLock(info: DoorPushInfo, overview: DoorOverview?): DoorLock? {
        overview ?: return null
        if (!overview.me.canAct) return null
        val camera = info.cameraId?.let { id -> overview.cameras.firstOrNull { it.id == id } }
        val lock = camera?.lockId?.let { id -> overview.locks.firstOrNull { it.id == id } }
            ?: overview.locks.singleOrNull()
            ?: return null
        return lock.takeIf { it.blocked == null && openAction(it) != null }
    }

    /** "Öffnen" of a lock: unlatch (smart lock) or the buzzer (opener), else unlock; null when none is allowed. */
    fun openAction(lock: DoorLock): String? = listOf(DoorAction.OPEN, DoorAction.UNLOCK)
        .firstOrNull { it in lock.actions && DoorLogic.allowedByRight(lock.right, it) }

    /** How long the window stays before it goes by itself (mockup: 30 s with a bar). */
    const val AUTO_HIDE_MS = 30_000L
}
