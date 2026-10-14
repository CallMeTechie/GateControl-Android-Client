package com.gatecontrol.android.ui.familie

import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.money.AllowanceBooking
import com.gatecontrol.android.familie.money.AllowancePayload
import com.gatecontrol.android.familie.money.AllowanceRuleDraft
import com.gatecontrol.android.familie.push.FamiliePushEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Taschengeld (mockup AppAllowance): balance, stars → money, bookings; parents book and set the rule. */
@HiltViewModel
class AllowanceViewModel @Inject constructor(
    private val api: FamilieApi,
    repository: FamilieRepository,
) : FamilieLoadViewModel<AllowancePayload>(repository) {

    init {
        start()
    }

    override suspend fun load(): FamilieResult<AllowancePayload> = api.allowance()

    override fun refreshOn(event: FamiliePushEvent): Boolean =
        event is FamiliePushEvent.Allowance || event is FamiliePushEvent.TaskDone || event is FamiliePushEvent.Other

    /** Parents: money in ([cents] > 0, `manual`) or a spending (`spend`, negative). */
    fun book(childId: Int, cents: Int, note: String, spend: Boolean) = act(R.string.familie_allowance_booked) {
        if (spend) api.allowanceBooking(childId, -kotlin.math.abs(cents), note, AllowanceBooking.KIND_SPEND)
        else api.allowanceBooking(childId, kotlin.math.abs(cents), note, AllowanceBooking.KIND_MANUAL)
    }

    fun deleteBooking(childId: Int, bookingId: Int) = act { api.deleteAllowanceBooking(childId, bookingId) }

    fun saveRule(childId: Int, draft: AllowanceRuleDraft) = act(R.string.familie_saved) { api.allowanceRule(childId, draft) }

    /** A child asks to convert [stars]. */
    fun convert(childId: Int, stars: Int) = act(R.string.familie_allowance_convert_sent) { api.allowanceConvert(childId, stars) }

    fun decide(requestId: Int, approve: Boolean) =
        act(if (approve) R.string.familie_allowance_approved else R.string.familie_allowance_declined) { api.allowanceDecide(requestId, approve) }
}
