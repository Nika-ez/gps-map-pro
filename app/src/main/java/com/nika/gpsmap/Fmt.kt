package com.nika.gpsmap

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Fmt {

    fun speedValue(mps: Float, units: UnitSystem): String {
        val value = when (units) {
            UnitSystem.METRIC -> mps * 3.6f
            UnitSystem.IMPERIAL -> mps * 2.2369363f
        }
        return String.format(Locale.US, "%.1f", value)
    }

    fun speedWithUnit(mps: Float, units: UnitSystem): String {
        return if (units == UnitSystem.METRIC) {
            "km/h: ${speedValue(mps, units)}"
        } else {
            "mph: ${speedValue(mps, units)}"
        }
    }

    fun speedUnit(units: UnitSystem): String =
        if (units == UnitSystem.METRIC) "km/h" else "mph"

    fun speedCompact(mps: Float, units: UnitSystem): String =
        speedValue(mps, units)

    fun distance(meters: Double, units: UnitSystem): String {
        return if (units == UnitSystem.METRIC) {
            if (meters < 1000.0) String.format(Locale.US, "%.0f m", meters)
            else String.format(Locale.US, "%.2f km", meters / 1000.0)
        } else {
            val miles = meters / 1609.344
            String.format(Locale.US, "%.2f mi", miles)
        }
    }

    fun altitude(meters: Double, units: UnitSystem): String {
        return if (units == UnitSystem.METRIC) {
            String.format(Locale.US, "%.0f m", meters)
        } else {
            String.format(Locale.US, "%.0f ft", meters * 3.28084)
        }
    }

    fun accuracy(meters: Float): String = String.format(Locale.US, "GPS Acc: ±%.1fm", meters)

    fun heading(degrees: Float): String {
        val normalized = ((degrees % 360f) + 360f) % 360f
        return String.format(Locale.US, "%.0f° %s", normalized, cardinal(normalized))
    }

    fun cardinal(degrees: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = (((degrees % 360f) + 360f) % 360f / 45f).roundToInt() % 8
        return dirs[index]
    }

    fun fixAge(millis: Long): String {
        if (millis < 0L) return "—"
        val seconds = millis / 1000.0
        return if (seconds < 10.0) String.format(Locale.US, "%.1fs", seconds)
        else String.format(Locale.US, "%.0fs", seconds)
    }

    fun duration(millis: Long): String {
        if (millis <= 0L) return "00:00"
        val totalSeconds = millis / 1000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun eta(millis: Long): String {
        if (millis <= 0L) return "—"
        val totalMinutes = ((millis + 59_999L) / 60_000L).coerceAtLeast(1L)
        if (totalMinutes < 60L) return "$totalMinutes min"
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (minutes == 0L) "$hours h" else "$hours h $minutes min"
    }

    fun coordinates(lat: Double, lon: Double, dms: Boolean): String {
        return if (dms) {
            "${dmsValue(lat, true)} ${dmsValue(lon, false)}"
        } else {
            String.format(Locale.US, "%.5f, %.5f", lat, lon)
        }
    }

    private fun dmsValue(value: Double, isLatitude: Boolean): String {
        val absolute = abs(value)
        var degrees = absolute.toInt()
        val minutesFull = (absolute - degrees) * 60.0
        var minutes = minutesFull.toInt()
        var seconds = ((minutesFull - minutes) * 60.0).roundToInt()
        if (seconds == 60) {
            seconds = 0
            minutes += 1
        }
        if (minutes == 60) {
            minutes = 0
            degrees += 1
        }
        val hemisphere = when {
            isLatitude && value >= 0 -> "N"
            isLatitude -> "S"
            value >= 0 -> "E"
            else -> "W"
        }
        return String.format(Locale.US, "%d°%02d'%02d\"%s", degrees, minutes, seconds, hemisphere)
    }

    fun cacheSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun dateTime(millis: Long): String =
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))

    fun clock(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}
