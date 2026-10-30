package com.nika.gpsmap.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import com.nika.gpsmap.RoutePoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Draws the chosen start and destination and a straight dashed connector.
 * This is intentionally not a navigation route; turn-by-turn comes later.
 *
 * The projected path is cached until the map center, zoom or size changes.
 * Rotating the map only rotates the canvas, so a two-finger rotation no longer
 * re-projects every point of a 2,500 point track on every frame.
 */
class RouteOverlay : Overlay() {

    private var start: RoutePoint? = null
    private var destination: RoutePoint? = null
    private var routePath: List<GeoPoint> = emptyList()
    private val path = Path()

    private var startColor = Color.parseColor("#32D74B")
    private var destinationColor = Color.parseColor("#FF453A")
    private var lineColor = Color.parseColor("#0A84FF")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val lineOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(210, 255, 255, 255)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val centerProbe = GeoPoint(0.0, 0.0)
    private val pointScratch = Point()
    private val startPoint = Point()
    private val destinationPoint = Point()
    private val startGeo = GeoPoint(0.0, 0.0)
    private val destinationGeo = GeoPoint(0.0, 0.0)
    private var hasStartPoint = false
    private var hasDestinationPoint = false

    private var cachedLatitude = Double.NaN
    private var cachedLongitude = Double.NaN
    private var cachedZoom = Double.NaN
    private var cachedWidth = -1
    private var cachedHeight = -1
    private var cacheValid = false

    private var dashEffect: DashPathEffect? = null

    fun setColors(start: Int, destination: Int, line: Int) {
        startColor = start
        destinationColor = destination
        lineColor = line
    }

    fun setStart(point: RoutePoint?) {
        start = point
        cacheValid = false
    }

    fun setDestination(point: RoutePoint?) {
        destination = point
        cacheValid = false
    }

    /** Draws a recorded track as the active route line. */
    fun setPath(points: List<GeoPoint>) {
        routePath = points
        cacheValid = false
    }

    fun clear() {
        start = null
        destination = null
        routePath = emptyList()
        cacheValid = false
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val density = mapView.resources.displayMetrics.density
        updateProjectionCache(mapView)

        if (routePath.size >= 2) {
            lineOutline.strokeWidth = 6f * density
            linePaint.strokeWidth = 3.2f * density
            linePaint.color = lineColor
            linePaint.pathEffect = null
            canvas.drawPath(path, lineOutline)
            canvas.drawPath(path, linePaint)
        } else if (hasStartPoint && hasDestinationPoint) {
            val sx = startPoint.x.toFloat()
            val sy = startPoint.y.toFloat()
            val dx = destinationPoint.x.toFloat()
            val dy = destinationPoint.y.toFloat()

            lineOutline.strokeWidth = 5.4f * density
            linePaint.strokeWidth = 2.6f * density
            linePaint.color = lineColor
            linePaint.pathEffect = dashEffect ?: DashPathEffect(
                floatArrayOf(9f * density, 7f * density),
                0f
            ).also { dashEffect = it }
            canvas.drawLine(sx, sy, dx, dy, lineOutline)
            canvas.drawLine(sx, sy, dx, dy, linePaint)
            linePaint.pathEffect = null
        }

        if (hasStartPoint) {
            drawBadge(
                canvas,
                mapView,
                startPoint.x.toFloat(),
                startPoint.y.toFloat(),
                density,
                "S",
                startColor
            )
        }
        if (hasDestinationPoint) {
            drawBadge(
                canvas,
                mapView,
                destinationPoint.x.toFloat(),
                destinationPoint.y.toFloat(),
                density,
                "D",
                destinationColor
            )
        }
    }

    private fun updateProjectionCache(mapView: MapView) {
        val center = mapView.getMapCenter(centerProbe)
        val zoom = mapView.zoomLevelDouble
        val width = mapView.width
        val height = mapView.height
        if (cacheValid &&
            center.latitude == cachedLatitude &&
            center.longitude == cachedLongitude &&
            zoom == cachedZoom &&
            width == cachedWidth &&
            height == cachedHeight
        ) {
            return
        }
        cachedLatitude = center.latitude
        cachedLongitude = center.longitude
        cachedZoom = zoom
        cachedWidth = width
        cachedHeight = height
        cacheValid = true

        val projection = mapView.projection
        path.rewind()
        if (routePath.size >= 2) {
            var first = true
            for (geoPoint in routePath) {
                projection.toPixels(geoPoint, pointScratch)
                if (first) {
                    path.moveTo(pointScratch.x.toFloat(), pointScratch.y.toFloat())
                    first = false
                } else {
                    path.lineTo(pointScratch.x.toFloat(), pointScratch.y.toFloat())
                }
            }
        }

        hasStartPoint = start?.let {
            startGeo.setCoords(it.latitude, it.longitude)
            projection.toPixels(startGeo, startPoint)
            true
        } ?: false
        hasDestinationPoint = destination?.let {
            destinationGeo.setCoords(it.latitude, it.longitude)
            projection.toPixels(destinationGeo, destinationPoint)
            true
        } ?: false
    }

    private fun drawBadge(
        canvas: Canvas,
        mapView: MapView,
        x: Float,
        y: Float,
        density: Float,
        letter: String,
        color: Int
    ) {
        val radius = 11f * density
        canvas.save()
        // Keep the letters upright even when heading-up rotates the map.
        canvas.rotate(-mapView.mapOrientation, x, y)
        canvas.drawCircle(x, y + 2f * density, radius, shadowPaint)
        ringPaint.strokeWidth = 3f * density
        fillPaint.color = color
        canvas.drawCircle(x, y, radius, ringPaint)
        canvas.drawCircle(x, y, radius - 1.5f * density, fillPaint)
        textPaint.textSize = 9f * density
        val baseline = y - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter, x, baseline, textPaint)
        canvas.restore()
    }
}
