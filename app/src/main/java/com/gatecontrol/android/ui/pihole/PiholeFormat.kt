package com.gatecontrol.android.ui.pihole

/** Formats a non-negative second count as MM:SS (minutes may exceed 59, e.g. 60:00). */
internal fun formatMmSs(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}
