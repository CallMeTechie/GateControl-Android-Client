package com.gatecontrol.android.familie.locate

import com.gatecontrol.android.familie.LocateFailCode
import com.gatecontrol.android.familie.LocateMode

/**
 * Permission decisions of the Familie feature, free of Android so they are
 * unit-tested. "foreground" = fine or coarse location granted,
 * "background" = ACCESS_BACKGROUND_LOCATION ("Allow all the time").
 */
object FamiliePermissions {

    /** What answering one locate request does. */
    sealed interface AnswerPlan {
        data object Locate : AnswerPlan
        data class Fail(val code: LocateFailCode) : AnswerPlan
    }

    /**
     * A locate request arrived (auto mode) or the child tapped "send":
     * locating in the background needs "Allow all the time"; while the app
     * is on screen the foreground permission is enough.
     */
    fun answerPlan(foreground: Boolean, background: Boolean, appVisible: Boolean, locationOn: Boolean): AnswerPlan = when {
        !foreground -> AnswerPlan.Fail(LocateFailCode.NO_PERMISSION)
        !appVisible && !background -> AnswerPlan.Fail(LocateFailCode.NO_PERMISSION)
        !locationOn -> AnswerPlan.Fail(LocateFailCode.LOCATION_OFF)
        else -> AnswerPlan.Locate
    }

    /** Where the notification button "Standort senden" (confirm mode) runs. */
    enum class SendPath {
        /** Right in the notification (receiver): allowed to locate in the background. */
        BACKGROUND,

        /** A small on-screen confirmation that locates while visible. */
        SCREEN,

        /** No location permission at all: open the Kinder-Handy screen to grant it. */
        APP,
    }

    fun sendPath(foreground: Boolean, background: Boolean): SendPath = when {
        foreground && background -> SendPath.BACKGROUND
        foreground -> SendPath.SCREEN
        else -> SendPath.APP
    }

    /**
     * A child whose parents get the location automatically must allow it
     * all the time, or the answer fails while the phone is in the pocket.
     */
    fun childNeedsBackground(mode: LocateMode?, foreground: Boolean, background: Boolean): Boolean =
        mode == LocateMode.AUTO && !(foreground && background)

    /** Can place reminders run on this device? */
    enum class GeofenceReadiness {
        /** No Google Play services: geofences cannot be registered. */
        UNAVAILABLE,
        NEED_LOCATION,
        NEED_BACKGROUND,
        READY,
    }

    fun geofenceReadiness(playServices: Boolean, fine: Boolean, background: Boolean): GeofenceReadiness = when {
        !playServices -> GeofenceReadiness.UNAVAILABLE
        !fine -> GeofenceReadiness.NEED_LOCATION
        !background -> GeofenceReadiness.NEED_BACKGROUND
        else -> GeofenceReadiness.READY
    }
}
