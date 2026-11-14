package com.gatecontrol.android.familie.places

/**
 * Android allows an app 100 geofences. Two features share them (pure, unit-tested): the Familie plugin (place
 * reminders and rule places) and „Notizen & Aufgaben“ (the places of tasks, plugin 1.5.0). The Familie plugin comes
 * first and is never pushed out; the hub gets what is left, less a reserve. The one place that knows the numbers.
 */
object GeofenceBudget {
    /** Android's limit per app. */
    const val ANDROID_MAX = 100

    /** The most the Familie plugin registers (the server caps 50 reminders per person and 20 rule places). */
    const val FAMILY_MAX = 90

    /** Kept free for good: room for a change of the Familie plugin's places before the hub has asked again. */
    const val RESERVE = 4

    /** The hub's own fence around the area its places were chosen for (leaving it asks the server again). */
    const val MOVE_FENCE = 1

    /** The server never gives the hub more than this. */
    const val NOTIZEN_MAX = 60

    /**
     * How many places of tasks this phone can watch while the Familie plugin has [familyRegistered] registered — the
     * `budget` of `POST places/watch` (the server lowers its own number to it, never above).
     */
    fun notizen(familyRegistered: Int): Int =
        (ANDROID_MAX - RESERVE - MOVE_FENCE - familyRegistered.coerceIn(0, FAMILY_MAX)).coerceIn(0, NOTIZEN_MAX)
}
