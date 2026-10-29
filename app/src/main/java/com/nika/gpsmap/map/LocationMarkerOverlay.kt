package com.nika.gpsmap.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import com.nika.gpsmap.MarkerStyle
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
    private var style = MarkerStyle.DOT

    private val accent = Color.parseColor("#0A84FF")

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(accent, 38)
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1E")
        style = Paint.Style.FILL
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val arrowPath = Path()
    private val pinPath = Path()
    private val tailPath = Path()
    private val rect = RectF()

    fun setStyle(style: MarkerStyle) {
        this.style = style
    }

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

        when (style) {
            MarkerStyle.DOT -> drawDot(canvas, x, y, density)
            MarkerStyle.ARROW -> drawArrow(canvas, x, y, density)
            MarkerStyle.CAR -> drawCar(canvas, x, y, density)
            MarkerStyle.PIN -> drawPin(canvas, x, y, density)
        }
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, density: Float) {
        canvas.drawCircle(x, y, 10f * density, ringPaint)
        canvas.drawCircle(x, y, 7.4f * density, fillPaint)

        if (!hasHeading) return
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

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, density: Float) {
        outlinePaint.strokeWidth = 2.2f * density
        canvas.save()
        canvas.rotate(if (hasHeading) heading else 0f, x, y)
        arrowPath.reset()
        arrowPath.moveTo(x, y - 16f * density)
        arrowPath.lineTo(x + 9f * density, y + 10f * density)
        arrowPath.lineTo(x, y + 5f * density)
        arrowPath.lineTo(x - 9f * density, y + 10f * density)
        arrowPath.close()
        canvas.drawPath(arrowPath, outlinePaint)
        canvas.drawPath(arrowPath, fillPaint)
        canvas.restore()
    }

    private fun drawCar(canvas: Canvas, x: Float, y: Float, density: Float) {
        outlinePaint.strokeWidth = 2.2f * density
        canvas.save()
        canvas.rotate(if (hasHeading) heading else 0f, x, y)

        rect.set(x - 8.4f * density, y - 6.6f * density, x - 5.8f * density, y - 1.8f * density)
        canvas.drawRoundRect(rect, 1.2f * density, 1.2f * density, wheelPaint)
        rect.set(x + 5.8f * density, y - 6.6f * density, x + 8.4f * density, y - 1.8f * density)
        canvas.drawRoundRect(rect, 1.2f * density, 1.2f * density, wheelPaint)
        rect.set(x - 8.4f * density, y + 2.2f * density, x - 5.8f * density, y + 7f * density)
        canvas.drawRoundRect(rect, 1.2f * density, 1.2f * density, wheelPaint)
        rect.set(x + 5.8f * density, y + 2.2f * density, x + 8.4f * density, y + 7f * density)
        canvas.drawRoundRect(rect, 1.2f * density, 1.2f * density, wheelPaint)

        rect.set(x - 6.4f * density, y - 10.5f * density, x + 6.4f * density, y + 10.5f * density)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, outlinePaint)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, fillPaint)

        rect.set(x - 4.2f * density, y - 7f * density, x + 4.2f * density, y - 2f * density)
        canvas.drawRoundRect(rect, 1.6f * density, 1.6f * density, glassPaint)
        rect.set(x - 4.2f * density, y + 2.6f * density, x + 4.2f * density, y + 6.6f * density)
        canvas.drawRoundRect(rect, 1.6f * density, 1.6f * density, glassPaint)

        canvas.drawCircle(x - 3.8f * density, y - 9.2f * density, 1f * density, glassPaint)
        canvas.drawCircle(x + 3.8f * density, y - 9.2f * density, 1f * density, glassPaint)
        canvas.restore()
    }

    private fun drawPin(canvas: Canvas, x: Float, y: Float, density: Float) {
        outlinePaint.strokeWidth = 2.4f * density
        val centerY = y - 17f * density
        val radius = 8.2f * density
        pinPath.reset()
        pinPath.addCircle(x, centerY, radius, Path.Direction.CW)

        tailPath.reset()
        tailPath.moveTo(x - radius * 0.78f, centerY + radius * 0.52f)
        tailPath.lineTo(x, y)
        tailPath.lineTo(x + radius * 0.78f, centerY + radius * 0.52f)
        tailPath.close()
        pinPath.op(tailPath, Path.Op.UNION)

        canvas.drawPath(pinPath, outlinePaint)
        canvas.drawPath(pinPath, fillPaint)
        canvas.drawCircle(x, centerY, 2.9f * density, glassPaint)
    }
}
