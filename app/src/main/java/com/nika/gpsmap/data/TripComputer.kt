package com.nika.gpsmap.data

import android.location.Location
import kotlin.math.max

class TripComputer {

    var distanceMeters: Double = 0.0
        private set
    var maxSpeedMps: Float = 0f
        private set
    var startElapsed: Long = 0L
        private set
    private var movingMillis: Long = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastElapsed = 0L

    fun onLocation(location: Location, nowElapsed: Long) {
        if (startElapsed == 0L) {
            startElapsed = nowElapsed
            lastElapsed = nowElapsed
            lastLat = location.latitude
            lastLon = location.longitude
            return
        }
        if (location.hasAccuracy() && location.accuracy > 40f) return

        val speed = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else 0f
        if (speed.isFinite() && speed < 55f) {
            maxSpeedMps = max(maxSpeedMps, speed)
        }

        if (lastLat.isNaN() || lastLon.isNaN()) {
            lastLat = location.latitude
            lastLon = location.longitude
            lastElapsed = nowElapsed
            return
        }

        val dt = nowElapsed - lastElapsed
        if (dt <= 0L) return
        val result = FloatArray(1)
        Location.distanceBetween(lastLat, lastLon, location.latitude, location.longitude, result)
        val meters = result[0].toDouble()
        if (meters in 1.5..200.0 && meters / (dt / 1000.0) <= 55.0) {
            distanceMeters += meters
            if (speed > 0.5f) movingMillis += dt.coerceAtMost(10_000L)
        }
        lastLat = location.latitude
        lastLon = location.longitude
        lastElapsed = nowElapsed
    }

    fun elapsedMillis(nowElapsed: Long): Long =
        if (startElapsed == 0L) 0L else (nowElapsed - startElapsed).coerceAtLeast(0L)

    fun averageSpeedMps(): Float {
        if (movingMillis < 1000L || distanceMeters <= 0.0) return 0f
        return (distanceMeters / (movingMillis / 1000.0)).toFloat()
    }

    fun reset() {
        distanceMeters = 0.0
        maxSpeedMps = 0f
        startElapsed = 0L
        movingMillis = 0L
        lastLat = Double.NaN
        lastLon = Double.NaN
        lastElapsed = 0L
    }
}
