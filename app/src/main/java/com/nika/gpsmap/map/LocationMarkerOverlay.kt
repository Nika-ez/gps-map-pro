package com.nika.gpsmap.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.ColorUtils
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class LocationMarkerOverlay : Overlay() {

    private var latitude = 0.0
    private var longitude = 0.0
    private var accuracyMeters = 0f
    private var heading = 0f
    private var hasHeading = false
    private var hasFix = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A84FF") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
    }
    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.parseColor("#0A84FF"), 38)
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val arrowPath = Path()

    fun update(lat: Double, lon: Double, accuracy: Float, headingDegrees: Float?, hasFix: Boolean) {
        latitude = lat
        longitude = lon
        accuracyMeters = accuracy
        if (headingDegrees != null && !headingDegrees.isNaN()) {
            heading = headingDegrees
            hasHeading = true
        }
        this.hasFix = hasFix
    }

    fun clear() {
        hasFix = false
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !hasFix) return
        val point = mapView.projection.toPixels(GeoPoint(latitude, longitude), null) ?: return
        val density = mapView.resources.displayMetrics.density
        val x = point.x.toFloat()
        val y = point.y.toFloat()

        if (accuracyMeters > 0f) {
            runCatching {
                val radius = mapView.projection.metersToPixels(accuracyMeters)
                    .coerceIn(10f * density, 420f * density)
                canvas.drawCircle(x, y, radius, accuracyPaint)
            }
        }

        canvas.drawCircle(x, y, 10f * density, ringPaint)
        fillPaint.color = Color.parseColor("#0A84FF")
        canvas.drawCircle(x, y, 7.4f * density, fillPaint)

        if (hasHeading) {
            canvas.save()
            canvas.rotate(heading, x, y)
            arrowPath.reset()
            arrowPath.moveTo(x, y - 20f * density)
            arrowPath.lineTo(x + 5.4f * density, y - 10.5f * density)
            arrowPath.lineTo(x, y - 12.6f * density)
            arrowPath.lineTo(x - 5.4f * density, y - 10.5f * density)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
            canvas.restore()
        }
    }
}
