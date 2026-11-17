package com.gatecontrol.android.familie.places

/**
 * Android allows an app 100 geofences. Three features share them (pure, unit-tested): the Familie plugin (place
 * reminders and rule places), the Ortsnachrichten of the chats (Messenger 1.27, a share of [PLACE_MESSAGES_MAX]) and
 * „Notizen & Aufgaben“ (the places of tasks, plugin 1.5.0). The Familie plugin comes first and is never pushed out; the
 * Ortsnachrichten take their share of what is left, the hub the rest, less a reserve. The one place that knows the
 * numbers.
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

    /** The share of the Ortsnachrichten (Messenger 1.27: at most 10 active per person anyway). */
    const val PLACE_MESSAGES_MAX = 10

    /** How many Ortsnachrichten this phone can watch while the Familie plugin has [familyRegistered] registered. */
    fun placeMessages(familyRegistered: Int): Int =
        (ANDROID_MAX - RESERVE - MOVE_FENCE - familyRegistered.coerceIn(0, FAMILY_MAX)).coerceIn(0, PLACE_MESSAGES_MAX)

    /**
     * How many places of tasks this phone can watch while the Familie plugin has [familyRegistered] registered and the
     * Ortsnachrichten [placeMessages] — the `budget` of `POST places/watch` (the server lowers its own number to it,
     * never above).
     */
    fun notizen(familyRegistered: Int, placeMessages: Int = 0): Int =
        (ANDROID_MAX - RESERVE - MOVE_FENCE - familyRegistered.coerceIn(0, FAMILY_MAX) - placeMessages.coerceIn(0, PLACE_MESSAGES_MAX))
            .coerceIn(0, NOTIZEN_MAX)

    /**
     * The Familie plugin is about to register [family] fences: which of the hub's [registered] ones (in the server's
     * order, the most important first) may stay — the rest make room first, so the family's are never refused.
     */
    fun keep(registered: List<String>, family: Int, placeMessages: Int = 0): List<String> = registered.take(notizen(family, placeMessages))
}
