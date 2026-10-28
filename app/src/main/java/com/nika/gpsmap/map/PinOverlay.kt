package com.nika.gpsmap.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class PinOverlay : Overlay() {

    private var latitude: Double? = null
    private var longitude: Double? = null
    private var accent = Color.parseColor("#32D74B")

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 0, 0, 0) }
    private val pinPath = Path()

    fun setPalette(color: Int) {
        accent = color
    }

    fun show(lat: Double, lon: Double) {
        latitude = lat
        longitude = lon
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
        val lat = latitude ?: return
        val lon = longitude ?: return
        val point = mapView.projection.toPixels(GeoPoint(lat, lon), null) ?: return
        val density = mapView.resources.displayMetrics.density
        val x = point.x.toFloat()
        val y = point.y.toFloat()

        canvas.save()
        canvas.rotate(-mapView.mapOrientation, x, y)

        val headY = y - 30f * density
        val radius = 10f * density

        canvas.drawOval(
            x - 4f * density,
            y - 1.5f * density,
            x + 4f * density,
            y + 2.5f * density,
            shadowPaint
        )

        pinPath.reset()
        pinPath.moveTo(x, y)
        pinPath.cubicTo(
            x - 2.4f * density, y - 7f * density,
            x - radius, y - 12f * density,
            x - radius, headY + radius * 0.45f
        )
        pinPath.cubicTo(
            x - radius, headY - radius * 0.65f,
            x - radius * 0.62f, headY - radius,
            x, headY - radius
        )
        pinPath.cubicTo(
            x + radius * 0.62f, headY - radius,
            x + radius, headY - radius * 0.65f,
            x + radius, headY + radius * 0.45f
        )
        pinPath.cubicTo(
            x + radius, y - 12f * density,
            x + 2.4f * density, y - 7f * density,
            x, y
        )
        pinPath.close()

        fillPaint.color = accent
        canvas.drawPath(pinPath, fillPaint)
        canvas.drawPath(pinPath, ringPaint)
        canvas.drawCircle(x, headY, 3.2f * density, dotPaint)
        canvas.restore()
    }
}
