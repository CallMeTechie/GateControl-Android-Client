package com.gatecontrol.android.common

import java.util.Locale

object Formatters {

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond < 1024) return "$bytesPerSecond B/s"
        val kb = bytesPerSecond / 1024.0
        if (kb < 1024.0) {
            return if (kb >= 100.0) {
                String.format(Locale.US, "%.0f KB/s", kb)
            } else {
                String.format(Locale.US, "%.1f KB/s", kb)
            }
        }
        val mb = kb / 1024.0
        if (mb < 1024.0) {
            return if (mb >= 100.0) {
                String.format(Locale.US, "%.0f MB/s", mb)
            } else {
                String.format(Locale.US, "%.1f MB/s", mb)
            }
        }
        val gb = mb / 1024.0
        return if (gb >= 100.0) {
            String.format(Locale.US, "%.0f GB/s", gb)
        } else {
            String.format(Locale.US, "%.1f GB/s", gb)
        }
    }

    fun formatDuration(totalSeconds: Long): String {
        if (totalSeconds < 60) return "<1m"
        val minutes = (totalSeconds / 60) % 60
        val hours = (totalSeconds / 3600) % 24
        val days = totalSeconds / 86400
        return when {
            days > 0 && hours > 0 -> "${days}d ${hours}h"
            days > 0 -> "${days}d"
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    fun formatHandshakeAge(seconds: Long, locale: String = "en"): String {
        val isDe = locale.equals("de", ignoreCase = true)
        return when {
            seconds < 1 -> if (isDe) "jetzt" else "now"
            seconds < 60 -> if (isDe) "vor ${seconds}s" else "${seconds}s ago"
            seconds < 3600 -> {
                val m = seconds / 60
                if (isDe) "vor ${m}m" else "${m}m ago"
            }
            else -> {
                val h = seconds / 3600
                if (isDe) "vor ${h}h" else "${h}h ago"
            }
        }
    }
}
