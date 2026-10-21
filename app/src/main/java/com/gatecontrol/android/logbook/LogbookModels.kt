package com.gatecontrol.android.logbook

/** Plugin id of "Fahrzeuge" (gatecontrol-plugins `gatecontrol-skoda`), whose app API has the logbook (1.6+). */
const val SKODA_PLUGIN = "gatecontrol-skoda"

/** Purpose of a trip (`purpose`); null = still open ("Zweck fehlt"). */
object LogbookPurpose {
    const val PRIVATE = "private"
    const val BUSINESS = "business"
    const val COMMUTE = "commute"

    /** Order of the buttons and sums: Arbeitsweg, Geschäftlich, Privat (mockup). */
    val ALL = listOf(COMMUTE, BUSINESS, PRIVATE)

    fun parse(value: String?): String? = value?.takeIf { it in ALL }
}

/** A point (WGS 84). */
data class LogbookPoint(val lat: Double, val lon: Double)

/** One of the token owner's vehicles. */
data class LogbookVehicle(val id: Long, val name: String, val model: String?)

/** Sums of the month (km) and the trip counts. */
data class LogbookSummary(
    val total: Double,
    val private: Double,
    val business: Double,
    val commute: Double,
    val open: Double,
    val openCount: Int,
    val count: Int,
) {
    fun km(purpose: String?): Double = when (purpose) {
        LogbookPurpose.PRIVATE -> private
        LogbookPurpose.BUSINESS -> business
        LogbookPurpose.COMMUTE -> commute
        else -> open
    }

    companion object {
        val EMPTY = LogbookSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0)
    }
}

/**
 * The way of a trip: [kind] `road` (calculated street route) or `line`
 * (straight line only: a detour or stop, or no route possible — drawn
 * dashed); [points] at least 2.
 */
data class LogbookRoute(val kind: String, val km: Double?, val points: List<LogbookPoint>) {
    val isRoad: Boolean get() = kind == KIND_ROAD

    companion object {
        const val KIND_ROAD = "road"
        const val KIND_LINE = "line"
    }
}

/** Who drove. */
data class LogbookDriver(val id: Long?, val name: String)

/** One trip of the logbook. */
data class LogbookTrip(
    val id: Long,
    /** `YYYY-MM-DD` (local day of the start). */
    val date: String,
    val dateLabel: String?,
    /** `HH:MM`, local. */
    val startTime: String?,
    val endTime: String?,
    val startedAt: String?,
    val endedAt: String?,
    val startKm: Double?,
    val endKm: Double?,
    val distanceKm: Double?,
    val startAddress: String?,
    val endAddress: String?,
    val start: LogbookPoint?,
    val end: LogbookPoint?,
    /** private | business | commute, null = open. */
    val purpose: String?,
    val note: String?,
    /** auto | manual */
    val source: String?,
    val edited: Boolean,
    val driver: LogbookDriver?,
    /** null = not calculated (yet) or no coordinates. */
    val route: LogbookRoute?,
)

/** Answer of `GET app/logbook`: one month of one vehicle. */
data class LogbookMonth(
    /** `YYYY-MM` shown. */
    val month: String,
    /** `YYYY-MM` of today on the server: no later month exists. */
    val currentMonth: String,
    val vehicles: List<LogbookVehicle>,
    /** null = no own vehicle. */
    val vehicleId: Long?,
    val canEdit: Boolean,
    val summary: LogbookSummary,
    val moving: Boolean,
    /** Newest first. */
    val trips: List<LogbookTrip>,
) {
    val vehicle: LogbookVehicle? get() = vehicles.firstOrNull { it.id == vehicleId }
}

/** Fields of `PUT app/logbook/trips/<id>`; null = unchanged, purpose "" = open. */
data class LogbookTripChange(
    val purpose: String? = null,
    val note: String? = null,
    val startAddress: String? = null,
    val endAddress: String? = null,
)

/** Answer of `GET app/logbook/export`, decoded from base64. Kept in memory until it is shared. */
class LogbookExportFile(val name: String, val type: String, val bytes: ByteArray)
