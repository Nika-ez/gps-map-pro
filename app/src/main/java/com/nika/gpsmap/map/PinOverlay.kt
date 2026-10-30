package com.nika.gpsmap.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class PinOverlay : Overlay() {

    private var latitude: Double? = null
    private var longitude: Double? = null
    private var accent = Color.parseColor("#32D74B")

    private val pinGeoPoint = GeoPoint(0.0, 0.0)
    private val screenPoint = Point()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 0, 0, 0) }
    private val pinPath = Path()
    private var pinPathDensity = 0f

    fun setPalette(color: Int) {
        accent = color
    }

    fun show(lat: Double, lon: Double) {
        latitude = lat
        longitude = lon
        pinGeoPoint.setCoords(lat, lon)
    }

    fun hide() {
        latitude = null
        longitude = null
    }

    val isVisible: Boolean
        get() = latitude != null && longitude != null

    fun geoPoint(): GeoPoint? {
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        return GeoPoint(lat, lon)
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        if (latitude == null || longitude == null) return
        val point = mapView.projection.toPixels(pinGeoPoint, screenPoint) ?: return
        val density = mapView.resources.displayMetrics.density
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        ensurePinPath(density)

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(-mapView.mapOrientation)

        canvas.drawOval(
            -4f * density,
            -1.5f * density,
            4f * density,
            2.5f * density,
            shadowPaint
        )

        fillPaint.color = accent
        canvas.drawPath(pinPath, fillPaint)
        canvas.drawPath(pinPath, ringPaint)
        canvas.drawCircle(0f, -30f * density, 3.2f * density, dotPaint)
        canvas.restore()
    }

    /**
     * The pin shape only depends on the display density. Building it once in
     * local coordinates avoids recreating four cubic curves and a Path union on
     * every map frame.
     */
    private fun ensurePinPath(density: Float) {
        if (pinPathDensity == density) return
        pinPathDensity = density
        val headY = -30f * density
        val radius = 10f * density
        pinPath.reset()
        pinPath.moveTo(0f, 0f)
        pinPath.cubicTo(
            -2.4f * density, -7f * density,
            -radius, -12f * density,
            -radius, headY + radius * 0.45f
        )
        pinPath.cubicTo(
            -radius, headY - radius * 0.65f,
            -radius * 0.62f, headY - radius,
            0f, headY - radius
        )
        pinPath.cubicTo(
            radius * 0.62f, headY - radius,
            radius, headY - radius * 0.65f,
            radius, headY + radius * 0.45f
        )
        pinPath.cubicTo(
            radius, -12f * density,
            2.4f * density, -7f * density,
            0f, 0f
        )
        pinPath.close()
    }
}
