package com.gatecontrol.android.familie

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** A search hit of [FamilieGeocoder.search]. */
data class GeoHit(val label: String, val lat: Double, val lon: Double)

/**
 * Addresses through Android's own Geocoder (the device's provider, often
 * Google), only when the device has one — never required: without it the
 * map and coordinates still work.
 */
object FamilieGeocoder {

    private const val TIMEOUT_MS = 8_000L

    fun available(): Boolean = Geocoder.isPresent()

    /** "Lindenstraße 4, Musterstadt" for a position, or null. */
    suspend fun address(context: Context, lat: Double, lon: Double, locale: Locale = Locale.getDefault()): String? {
        if (!available()) return null
        val list = withTimeoutOrNull(TIMEOUT_MS) { fromLocation(Geocoder(context, locale), lat, lon) } ?: return null
        return list.firstOrNull()?.let(::label)
    }

    /** Places matching [query] (address, shop name …), at most 5. */
    suspend fun search(context: Context, query: String, locale: Locale = Locale.getDefault()): List<GeoHit> {
        if (!available() || query.isBlank()) return emptyList()
        val list = withTimeoutOrNull(TIMEOUT_MS) { fromName(Geocoder(context, locale), query.trim()) } ?: return emptyList()
        return list.filter { it.hasLatitude() && it.hasLongitude() }.map { GeoHit(label(it), it.latitude, it.longitude) }
    }

    private fun label(a: Address): String {
        val line = a.getAddressLine(0)
        if (!line.isNullOrBlank()) return line
        return listOfNotNull(a.thoroughfare?.let { t -> listOfNotNull(t, a.subThoroughfare).joinToString(" ") }, a.locality)
            .joinToString(", ")
            .ifBlank { a.featureName.orEmpty() }
    }

    private suspend fun fromLocation(g: Geocoder, lat: Double, lon: Double): List<Address> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                g.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { g.getFromLocation(lat, lon, 1) }.getOrNull().orEmpty()
            }
        }

    private suspend fun fromName(g: Geocoder, query: String): List<Address> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                g.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { g.getFromLocationName(query, 5) }.getOrNull().orEmpty()
            }
        }
}
