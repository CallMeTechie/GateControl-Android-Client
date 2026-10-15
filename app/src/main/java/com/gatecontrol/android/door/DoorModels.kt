package com.gatecontrol.android.door

/** Plugin id of "Tür & Kamera" (gatecontrol-plugins `gatecontrol-door`). */
const val DOOR_PLUGIN = "gatecontrol-door"

/** Lock actions of the app API (`POST locks/<id>/<action>`). */
object DoorAction {
    const val LOCK = "lock"
    const val UNLOCK = "unlock"
    /** Smart lock: unlatch ("öffnen"); opener: electric strike ("Summer"). */
    const val OPEN = "open"
    val ALL = listOf(LOCK, UNLOCK, OPEN)
}

/** Right of a person on one lock. */
object DoorRight {
    const val VIEW = "view"
    const val LOCK = "lock"
    const val OPEN = "open"
    val ALL = setOf(VIEW, LOCK, OPEN)
}

/** Why a lock's buttons are disabled right now (`blocked`). */
object DoorBlocked {
    /** Kiosk device / device-trust viewer: never actions. */
    const val READ_ONLY = "read_only"
    const val OUTSIDE_WINDOW = "outside_window"
}

/** Weekly time window of a person (`days` 1 = Monday … 7, `from`/`to` HH:MM). */
data class DoorWindow(val days: List<Int>, val from: String, val to: String)

/** `me.me`: the person and their general settings. */
data class DoorPerson(
    val userId: Long,
    val name: String,
    val canAct: Boolean,
    val autoUnlock: Boolean,
    val window: DoorWindow?,
    val windowOpen: Boolean,
)

/** `LockView.state`. [door] null = no door sensor. */
data class DoorLockState(
    /** smart lock: locked unlocked unlatched locking unlocking unlatching jammed uncalibrated unknown; opener: online rto_active open opening untrained boot_run unknown. */
    val lock: String,
    val door: String?,
    val batteryCritical: Boolean,
    val batteryPercent: Int?,
    val batteryCharging: Boolean,
    val reachable: Boolean,
    val updatedAt: String?,
)

/** `LockView`: one lock as this person sees it. */
data class DoorLock(
    val id: Long,
    val name: String,
    /** smartlock | opener */
    val type: String,
    /** view | lock | open */
    val right: String,
    /** Buttons to show (right ∩ configured). */
    val actions: List<String>,
    /** null = usable now; read_only | outside_window (or a future reason) = buttons disabled. */
    val blocked: String?,
    /** Actions to confirm first. */
    val confirm: List<String>,
    val doorSensor: Boolean,
    val state: DoorLockState,
) {
    val isOpener: Boolean get() = type == TYPE_OPENER

    companion object {
        const val TYPE_SMARTLOCK = "smartlock"
        const val TYPE_OPENER = "opener"
    }
}

/** This person's pushes for one camera. */
data class DoorCameraPrefs(val ding: Boolean, val motion: Boolean)

/** `CameraView`. */
data class DoorCamera(
    val id: Long,
    val name: String,
    val kind: String?,
    val doorbell: Boolean,
    val lockId: Long?,
    val online: Boolean?,
    val battery: Int?,
    val prefs: DoorCameraPrefs,
    val lastImageAt: String?,
)

/** Ring connection (unofficial interface): [broken] → "Ring: gestört". */
data class DoorRing(val connected: Boolean, val broken: Boolean)

/** Answer of `GET me`. */
data class DoorOverview(
    val me: DoorPerson,
    val locks: List<DoorLock>,
    val cameras: List<DoorCamera>,
    val ring: DoorRing,
    val serverTime: String?,
)

/** Answer of `POST locks/<id>/<action>`. */
data class DoorActionResult(val message: String?, val lock: DoorLock?)

/** A picture (camera snapshot or event picture), decoded from base64. Kept in memory only. */
class DoorImage(val mime: String, val at: String?, val bytes: ByteArray)

/** Answer of `GET cameras/<id>/snapshot`. */
class DoorSnapshot(val cameraId: Long, val image: DoorImage)

/** One history entry. */
data class DoorHistoryItem(
    val id: Long,
    val at: String?,
    /** action | ding | motion | alert | nuki */
    val kind: String,
    val lockId: Long?,
    val lockName: String?,
    val cameraId: Long?,
    val cameraName: String?,
    val userId: Long?,
    val userName: String?,
    /** app | portal | notification | automation | system | nuki | keypad | ring */
    val via: String?,
    /** automation: the calling plugin id. */
    val caller: String?,
    val action: String?,
    /** ok | refused | failed */
    val result: String?,
    val reason: String?,
    val reasonText: String?,
    val hasImage: Boolean,
) {
    /** Rings and motion events open their detail (`GET events/<id>`). */
    val hasDetail: Boolean get() = kind == KIND_DING || kind == KIND_MOTION

    companion object {
        const val KIND_ACTION = "action"
        const val KIND_DING = "ding"
        const val KIND_MOTION = "motion"
        const val KIND_ALERT = "alert"
        const val KIND_NUKI = "nuki"
        val KINDS = setOf(KIND_ACTION, KIND_DING, KIND_MOTION, KIND_ALERT, KIND_NUKI)
    }
}

/** One page of `GET history`; [nextCursor] null = no more. */
data class DoorHistoryPage(val items: List<DoorHistoryItem>, val nextCursor: Long?)

/** Answer of `GET events/<id>`: the event, its picture (or none) and the linked lock (or none). */
class DoorEventDetail(val item: DoorHistoryItem, val image: DoorImage?, val lock: DoorLock?)
