package com.gatecontrol.android.familie.locate

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.LocateFailCode
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.location.LocateResult
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What happened to one locate request on the child's phone. */
sealed interface AnswerOutcome {
    data class Sent(val accuracyM: Int) : AnswerOutcome

    /** The phone could not locate; the parent was told why. */
    data class Failed(val code: LocateFailCode) : AnswerOutcome

    /** The server refused or could not be reached ([code]: not pending any more, offline, …). */
    data class NotDelivered(val code: String) : AnswerOutcome

    /** Already being answered. */
    data object Busy : AnswerOutcome
}

/**
 * Answers a parent's locate request on the child's phone: one fresh fix
 * (high accuracy, at most 30 s, else a known position up to 2 minutes old)
 * and `POST locate/requests/<id>/answer`, or `…/fail` with the reason.
 * Runs in the background for "automatisch" (push stream) and from the
 * notification / Kinder-Handy screen for "ich bestätige".
 */
@Singleton
class LocateAnswerer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: FamilieApi,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()

    suspend fun answer(requestId: Int, appVisible: Boolean = isAppVisible()): AnswerOutcome {
        if (!inFlight.add(requestId)) return AnswerOutcome.Busy
        try {
            val plan = FamiliePermissions.answerPlan(
                foreground = DeviceLocator.hasPermission(context),
                background = DeviceLocator.hasBackgroundPermission(context),
                appVisible = appVisible,
                locationOn = DeviceLocator.isLocationOn(context),
            )
            if (plan is FamiliePermissions.AnswerPlan.Fail) return fail(requestId, plan.code)

            return when (val result = DeviceLocator.locate(context, timeoutMs = FIX_TIMEOUT_MS, staleMs = LAST_KNOWN_MS)) {
                is LocateResult.Fix -> {
                    val loc = result.location
                    val at = Instant.ofEpochMilli(loc.time.takeIf { it > 0 } ?: System.currentTimeMillis())
                        .truncatedTo(ChronoUnit.SECONDS).toString()
                    val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0
                    when (val r = api.answer(requestId, loc.latitude, loc.longitude, accuracy, at, battery())) {
                        is FamilieResult.Ok -> AnswerOutcome.Sent(accuracy.toInt())
                        else -> AnswerOutcome.NotDelivered(r.errorCode() ?: "failed")
                    }
                }
                LocateResult.NoPermission -> fail(requestId, LocateFailCode.NO_PERMISSION)
                LocateResult.LocationOff -> fail(requestId, LocateFailCode.LOCATION_OFF)
                LocateResult.NoFix -> fail(requestId, LocateFailCode.TIMEOUT)
            }
        } finally {
            inFlight.remove(requestId)
        }
    }

    private suspend fun fail(requestId: Int, code: LocateFailCode): AnswerOutcome {
        Timber.i("Familie: locate request not answered (%s)", code.wire)
        api.fail(requestId, code)
        return AnswerOutcome.Failed(code)
    }

    private fun battery(): Int? = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
    }.getOrNull()

    companion object {
        private const val FIX_TIMEOUT_MS = 30_000L
        private const val LAST_KNOWN_MS = 2 * 60_000L

        /** An activity of the app is on screen (location is "in use"). */
        fun isAppVisible(): Boolean {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
}
