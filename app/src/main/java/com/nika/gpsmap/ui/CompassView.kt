package com.nika.gpsmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.nika.gpsmap.Palette
import kotlin.math.min

class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var azimuth: Float = 0f
        set(value) {
            field = ((value % 360f) + 360f) % 360f
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 22f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.DEFAULT
    }
    private val oval = RectF()

    private var light = false
    private var textColor = Color.WHITE

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Compass"
    }

    fun applyPalette(palette: Palette) {
        light = palette.isLight
        textColor = if (light) palette.text else Color.WHITE
        facePaint.color = if (light) Color.parseColor("#E7EBEF") else Color.parseColor("#333333")
        ringPaint.color = if (light) Color.parseColor("#66000000") else Color.WHITE
        arrowPaint.color = textColor
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (48f * density).toInt()
        setMeasuredDimension(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - ringPaint.strokeWidth / 2f

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawOval(oval, facePaint)
        canvas.drawOval(oval, ringPaint)

        canvas.save()
        canvas.rotate(-azimuth, cx, cy)
        val baseline = cy - (arrowPaint.ascent() + arrowPaint.descent()) / 2f
        canvas.drawText("▲", cx, baseline, arrowPaint)
        canvas.restore()
    }
}
