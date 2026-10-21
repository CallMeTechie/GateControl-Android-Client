package com.gatecontrol.android.logbook

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "n Fahrten zeigen" on the all-trips map: the logbook list shows only
 * these trips of [vehicleId] in [month], with [label] ("A ↔ B") as a chip.
 */
data class LogbookListFilter(val vehicleId: Long?, val month: String, val label: String?, val tripIds: Set<Long>)

/**
 * State shared by the logbook screens (list, all-trips map, one trip): the
 * months loaded in this app run, per login and vehicle. Kept in memory
 * only — trips with places and routes are never stored on the device.
 */
@Singleton
class LogbookRepository @Inject constructor(
    private val setupRepository: SetupRepository,
    private val api: LogbookApi,
) {
    private val _months = MutableStateFlow<Map<String, LogbookMonth>>(emptyMap())
    /** Loaded months by [key]. */
    val months: StateFlow<Map<String, LogbookMonth>> = _months.asStateFlow()

    /** Vehicle of the last answer: the screens opened without one show it. */
    @Volatile var lastVehicleId: Long? = null
        private set

    private val _listFilter = MutableStateFlow<LogbookListFilter?>(null)
    val listFilter: StateFlow<LogbookListFilter?> = _listFilter.asStateFlow()

    private fun owner(): String = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    fun key(vehicleId: Long?, month: String): String = "${owner()}|${vehicleId ?: 0}|$month"

    /** The month as loaded before, null when not (yet). */
    fun cached(vehicleId: Long?, month: String?): LogbookMonth? {
        val m = month ?: return null
        return _months.value[key(vehicleId ?: lastVehicleId, m)]
    }

    /** Asks the server; a success is kept under the vehicle and month it answered for. */
    suspend fun load(vehicleId: Long?, month: String?): LogbookResult<LogbookMonth> {
        val r = api.month(vehicleId, month)
        when (r) {
            is LogbookResult.Ok -> {
                val m = r.value
                lastVehicleId = m.vehicleId
                _months.update { it + (key(m.vehicleId, m.month) to m) + (key(vehicleId, m.month) to m) }
            }
            is LogbookResult.Failed -> {
                // No right (any more) or plugin gone: forget what this run kept.
                if (r.http == 403 || r.http == 404) clear()
            }
            else -> Unit
        }
        return r
    }

    /** Saves [change] of [tripId]; the saved trip replaces the old one in every loaded month. */
    suspend fun updateTrip(tripId: Long, change: LogbookTripChange): LogbookResult<LogbookTrip> {
        val r = api.updateTrip(tripId, change)
        if (r is LogbookResult.Ok) {
            _months.update { all -> all.mapValues { (_, m) -> LogbookLogic.withTrip(m, r.value) } }
        }
        return r
    }

    suspend fun export(vehicleId: Long, from: String, to: String, format: String): LogbookResult<LogbookExportFile> =
        api.export(vehicleId, from, to, format)

    /** The list shows only [filter]'s trips (null: all again). */
    fun setListFilter(filter: LogbookListFilter?) {
        _listFilter.value = filter
    }

    fun clear() {
        _months.value = emptyMap()
        _listFilter.value = null
        lastVehicleId = null
    }
}
