package com.nika.gpsmap.map

import org.osmdroid.util.GeoPoint
import java.util.ArrayDeque
import kotlin.math.cos

/**
 * Lightweight Ramer-Douglas-Peucker simplification for map display.
 *
 * Recorded tracks can contain thousands of sub-meter points. Drawing all of
 * them every frame makes long tracks laggy, so display copies are reduced to a
 * few metres of tolerance while the full points stay available for GPX export
 * and route progress.
 */
object GeoSimplify {

    private const val METERS_PER_DEGREE_LAT = 110_540.0
    private const val METERS_PER_DEGREE_LON = 111_320.0

    /**
     * Prepares a track for drawing. GPS fixes jitter by a few metres, which
     * makes a raw polyline look like a saw blade even on a straight road, so
     * the display copy is lightly smoothed, stripped of near-duplicate points
     * and then simplified to a vertex budget. The full point list is still used
     * for GPX export, distance and route progress.
     */
    fun simplifyForDisplay(points: List<GeoPoint>, maxPoints: Int = 2_500): List<GeoPoint> {
        if (points.size < 3) return points
        val cleaned = dropNearDuplicates(points)
        if (cleaned.size < 3) return cleaned

        val spacing = averageSpacingMeters(cleaned)
        val passes = when {
            cleaned.size < 8 -> 0
            spacing <= 4.0 -> 3
            spacing <= 10.0 -> 2
            spacing <= 20.0 -> 1
            else -> 0
        }
        var prepared = cleaned
        repeat(passes) { prepared = smoothOnce(prepared) }

        var tolerance = if (spacing <= 8.0) 3.0 else 2.5
        var result = simplify(prepared, tolerance)
        while (result.size > maxPoints && tolerance < 20.0) {
            tolerance *= 1.5
            result = simplify(prepared, tolerance)
        }
        return result
    }

    private fun dropNearDuplicates(points: List<GeoPoint>, minMeters: Double = 0.75): List<GeoPoint> {
        val result = ArrayList<GeoPoint>(points.size)
        result.add(points.first())
        val minSquared = minMeters * minMeters
        for (index in 1 until points.size - 1) {
            val candidate = points[index]
            if (distanceMeters(result.last(), candidate).let { it * it } >= minSquared) {
                result.add(candidate)
            }
        }
        result.add(points.last())
        return result
    }

    /**
     * One pass of a [1, 2, 1] binomial low-pass filter. Endpoints stay fixed so
     * the track still starts and ends exactly where it was recorded.
     */
    private fun smoothOnce(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 3) return points
        val result = ArrayList<GeoPoint>(points.size)
        result.add(points.first())
        for (index in 1 until points.size - 1) {
            val previous = points[index - 1]
            val current = points[index]
            val next = points[index + 1]
            result.add(
                GeoPoint(
                    (previous.latitude + 2.0 * current.latitude + next.latitude) / 4.0,
                    (previous.longitude + 2.0 * current.longitude + next.longitude) / 4.0
                )
            )
        }
        result.add(points.last())
        return result
    }

    private fun averageSpacingMeters(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (index in 1 until points.size) {
            total += distanceMeters(points[index - 1], points[index])
        }
        return total / (points.size - 1)
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val latitude = Math.toRadians((a.latitude + b.latitude) * 0.5)
        val dx = (b.longitude - a.longitude) * METERS_PER_DEGREE_LON * cos(latitude)
        val dy = (b.latitude - a.latitude) * METERS_PER_DEGREE_LAT
        return Math.hypot(dx, dy)
    }

    fun simplify(points: List<GeoPoint>, toleranceMeters: Double = 2.5): List<GeoPoint> {
        if (points.size < 3) return points
        val toleranceSquared = toleranceMeters * toleranceMeters
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val range = stack.removeLast()
            val first = range[0]
            val last = range[1]
            if (last <= first + 1) continue

            var farthestIndex = -1
            var farthestDistance = 0.0
            for (index in first + 1 until last) {
                val distance = distanceSquared(points[first], points[last], points[index])
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthestIndex = index
                }
            }
            if (farthestIndex != -1 && farthestDistance > toleranceSquared) {
                keep[farthestIndex] = true
                stack.addLast(intArrayOf(first, farthestIndex))
                stack.addLast(intArrayOf(farthestIndex, last))
            }
        }

        val result = ArrayList<GeoPoint>(points.size)
        for (index in points.indices) {
            if (keep[index]) result.add(points[index])
        }
        return result
    }

    private fun distanceSquared(a: GeoPoint, b: GeoPoint, point: GeoPoint): Double {
        val latitude = (a.latitude + b.latitude) * 0.5
        val lonScale = METERS_PER_DEGREE_LON * cos(Math.toRadians(latitude))
        val ax = (a.longitude - point.longitude) * lonScale
        val ay = (a.latitude - point.latitude) * METERS_PER_DEGREE_LAT
        val bx = (b.longitude - point.longitude) * lonScale
        val by = (b.latitude - point.latitude) * METERS_PER_DEGREE_LAT
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 0.0) 0.0
        else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val px = ax + dx * t
        val py = ay + dy * t
        return px * px + py * py
    }
}
