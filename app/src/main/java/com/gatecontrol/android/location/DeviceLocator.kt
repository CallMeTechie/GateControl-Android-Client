package com.gatecontrol.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Outcome of one location request. */
sealed interface LocateResult {
    data class Fix(val location: Location) : LocateResult
    data object NoPermission : LocateResult
    data object LocationOff : LocateResult
    data object NoFix : LocateResult
}

/**
 * One-shot position from the platform [LocationManager] (fused provider
 * when the device has one, else GPS, else network) — no Play services,
 * so the GitHub build works the same. Only ever called in the foreground
 * after the user tapped the satellite button.
 */
object DeviceLocator {

    private const val TIMEOUT_MS = 20_000L
    /** A cached position this young and this accurate is good enough. */
    private const val FRESH_MS = 30_000L
    private const val FRESH_ACCURACY_M = 50f
    /** Fallback when no new fix arrives in time. */
    private const val STALE_MS = 5 * 60_000L

    fun hasPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPrecisePermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun isLocationOn(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: false

    @Suppress("MissingPermission") // checked by hasPermission() first
    suspend fun locate(context: Context): LocateResult {
        if (!hasPermission(context)) return LocateResult.NoPermission
        val lm = context.getSystemService(LocationManager::class.java) ?: return LocateResult.NoFix
        if (!lm.isLocationEnabled) return LocateResult.LocationOff

        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.hasProvider(it) && lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return LocateResult.LocationOff

        val cached = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        cached.filter { ageMs(it) <= FRESH_MS && it.hasAccuracy() && it.accuracy <= FRESH_ACCURACY_M }
            .minByOrNull { it.accuracy }
            ?.let { return LocateResult.Fix(it) }

        val fresh = withTimeoutOrNull(TIMEOUT_MS) { current(context, lm, providers.first()) }
        if (fresh != null) return LocateResult.Fix(fresh)

        return cached.filter { ageMs(it) <= STALE_MS }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
            ?.let { LocateResult.Fix(it) }
            ?: LocateResult.NoFix
    }

    @Suppress("MissingPermission")
    private suspend fun current(context: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun ageMs(l: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}
