package com.sethu.emergency.comm.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe utility for consistent formatting of Unix epoch milliseconds
 * in the device's local time zone.
 *
 * Requirements:
 * - Formats epoch milliseconds exactly once in local time zone.
 * - No manual UTC offset addition.
 * - Gracefully handles missing, zero, or invalid timestamps.
 */
object TimeFormatter {

    /**
     * Formats Unix epoch milliseconds to "HH:mm:ss" in local time zone.
     * Returns "Not recorded" if timestamp is null or <= 0.
     */
    fun formatTime(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis <= 0L) return "Not recorded"
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
    }

    /**
     * Formats Unix epoch milliseconds to "yyyy-MM-dd HH:mm:ss" in local time zone.
     * Returns "Not recorded" if timestamp is null or <= 0.
     */
    fun formatDateTime(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis <= 0L) return "Not recorded"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
    }
}
