package com.nika.gpsmap.data

import android.location.Location

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speedMps: Float,
    val timeMillis: Long
)

class TrackRecorder {

    var isRecording: Boolean = false
        private set
    var startedAtMillis: Long = 0L
        private set
    val points = ArrayList<TrackPoint>()

    fun start() {
        points.clear()
        startedAtMillis = System.currentTimeMillis()
        isRecording = true
    }

    fun add(location: Location, nowMillis: Long = System.currentTimeMillis()) {
        if (!isRecording) return
        val candidate = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            timeMillis = nowMillis
        )
        if (points.isEmpty()) {
            points.add(candidate)
            return
        }
        val last = points.last()
        val dt = nowMillis - last.timeMillis
        val distance = FloatArray(1)
        Location.distanceBetween(
            last.latitude,
            last.longitude,
            candidate.latitude,
            candidate.longitude,
            distance
        )
        if (dt >= 2000L || distance[0] >= 4f) {
            if (distance[0] < 500f || dt >= 10_000L) {
                points.add(candidate)
            }
        }
    }

    fun stop(): List<TrackPoint> {
        isRecording = false
        return points.toList()
    }

    fun currentDurationMillis(now: Long = System.currentTimeMillis()): Long =
        if (!isRecording || startedAtMillis == 0L) 0L else (now - startedAtMillis).coerceAtLeast(0L)
}
