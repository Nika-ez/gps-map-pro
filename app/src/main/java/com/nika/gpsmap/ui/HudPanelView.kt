package com.nika.gpsmap.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.nika.gpsmap.Palette
import com.nika.gpsmap.R
import com.nika.gpsmap.ThemeMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class HudPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val expandedY = 0f
    private val miniY = dp(-140f)
    private val hiddenY = dp(-220f)

    private val expandedLayout: RelativeLayout
    private val miniLayout: RelativeLayout
    private val tvSpeed: TextView
    private val tvTime: TextView
    private val tvBattery: TextView
    private val tvExpSpeed: TextView
    private val tvExpTime: TextView
    private val tvExpBattery: TextView
    private val tvGpsAcc: TextView
    private val tvSatellites: TextView
    private val tvAltitude: TextView
    private val tvHeading: TextView
    private val tvFix: TextView
    private val tvTripDistance: TextView
    private val tvTripAvg: TextView
    private val tvTripMax: TextView
    private val tvCoords: TextView
    private val extraStats: LinearLayout
    private val actionRow: LinearLayout
    private val btnSettings: ImageButton
    private val btnResetTrip: ImageButton
    private val btnRecord: ImageButton
    private val btnTracks: ImageButton
    private val dragHandleArea: View
    private val dragBar: View
    private val compass: CompassView

    private var palette: Palette = Palette.from(context, ThemeMode.ORIGINAL)
    private var speedColor: Int = Color.WHITE
    private var morphDxSpeed = 0f
    private var morphDySpeed = 0f
    private var morphDxTime = 0f
    private var morphDyTime = 0f
    private var morphDxBattery = 0f
    private var morphDyBattery = 0f
    private var scaleSpeed = 1f
    private var scaleTime = 1f
    private var scaleBattery = 1f
    private var morphPosted = false
    private var initialized = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTranslation = 0f
    private var velocityTracker: VelocityTracker? = null
    private var spring: SpringAnimation? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val handler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable {
        if (autoCollapseEnabled && !autoCollapsePaused && translationY > miniY + 1f) {
            animateTo(miniY, 0f)
        }
    }

    var autoCollapseEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) handler.removeCallbacks(autoCollapseRunnable)
            else scheduleAutoCollapse()
        }

    private var autoCollapsePaused = false

    var onSettingsClick: (() -> Unit)? = null
    var onResetTripClick: (() -> Unit)? = null
    var onRecordClick: (() -> Unit)? = null
    var onTracksClick: (() -> Unit)? = null

    val isExpanded: Boolean
        get() = translationY > miniY + 10f

    val isRecordingButton: Boolean
        get() = recordingVisual

    private var recordingVisual = false

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.hud_panel, this, true)

        expandedLayout = findViewById(R.id.expandedLayout)
        miniLayout = findViewById(R.id.miniLayout)
        tvSpeed = findViewById(R.id.tvSharedKmh)
        tvTime = findViewById(R.id.tvSharedTime)
        tvBattery = findViewById(R.id.tvSharedBattery)
        tvExpSpeed = findViewById(R.id.tvExpPlaceholderKmh)
        tvExpTime = findViewById(R.id.tvExpPlaceholderTime)
        tvExpBattery = findViewById(R.id.tvExpPlaceholderBattery)
        tvGpsAcc = findViewById(R.id.tvGpsAcc)
        tvSatellites = findViewById(R.id.tvSatellites)
        tvAltitude = findViewById(R.id.tvAltitude)
        tvHeading = findViewById(R.id.tvHeading)
        tvFix = findViewById(R.id.tvFix)
        tvTripDistance = findViewById(R.id.tvTripDistance)
        tvTripAvg = findViewById(R.id.tvTripAvg)
        tvTripMax = findViewById(R.id.tvTripMax)
        tvCoords = findViewById(R.id.tvCoords)
        extraStats = findViewById(R.id.extraStats)
        actionRow = findViewById(R.id.actionRow)
        btnSettings = findViewById(R.id.btnSettings)
        btnResetTrip = findViewById(R.id.btnResetTrip)
        btnRecord = findViewById(R.id.btnRecord)
        btnTracks = findViewById(R.id.btnTracks)
        dragHandleArea = findViewById(R.id.dragHandleArea)
        dragBar = findViewById(R.id.dragBar)
        compass = findViewById(R.id.compass)

        btnSettings.setOnClickListener { onSettingsClick?.invoke() }
        btnResetTrip.setOnClickListener { onResetTripClick?.invoke() }
        btnRecord.setOnClickListener { onRecordClick?.invoke() }
        btnTracks.setOnClickListener { onTracksClick?.invoke() }

        dragHandleArea.setOnClickListener {
            if (translationY > (expandedY + miniY) / 2f) animateTo(expandedY, 0f)
            else animateTo(miniY, 0f)
        }
        miniLayout.setOnClickListener {
            if (translationY < miniY - 10f) animateTo(miniY, 0f)
            else animateTo(expandedY, 0f)
        }

        dragBar.backgroundTintList = ColorStateList.valueOf(palette.handle)
        applyPalette(palette)
    }

    fun applyPalette(newPalette: Palette) {
        palette = newPalette
        speedColor = newPalette.text
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(newPalette.panel)
            cornerRadii = floatArrayOf(
                0f, 0f,
                0f, 0f,
                dp(32f), dp(32f),
                dp(32f), dp(32f)
            )
        }

        tvSpeed.setTextColor(newPalette.text)
        tvTime.setTextColor(newPalette.text)
        tvBattery.setTextColor(newPalette.text)
        tvGpsAcc.setTextColor(newPalette.subtle)
        tvSatellites.setTextColor(newPalette.sat)
        tvAltitude.setTextColor(newPalette.text)
        tvHeading.setTextColor(newPalette.text)
        tvFix.setTextColor(newPalette.text)
        tvTripDistance.setTextColor(newPalette.text)
        tvTripAvg.setTextColor(newPalette.text)
        tvTripMax.setTextColor(newPalette.text)
        tvCoords.setTextColor(newPalette.extra)
        dragBar.backgroundTintList = ColorStateList.valueOf(newPalette.handle)

        ImageViewCompat.setImageTintList(btnSettings, ColorStateList.valueOf(newPalette.text))
        ImageViewCompat.setImageTintList(btnResetTrip, ColorStateList.valueOf(newPalette.text))
        ImageViewCompat.setImageTintList(btnTracks, ColorStateList.valueOf(newPalette.text))
        ImageViewCompat.setImageTintList(
            btnRecord,
            ColorStateList.valueOf(if (recordingVisual) newPalette.danger else newPalette.accent)
        )
        compass.applyPalette(newPalette)
        requestMorphUpdate()
    }

    fun setSpeed(text: String) {
        tvSpeed.text = text
        tvExpSpeed.text = text
        requestMorphUpdate()
    }

    fun setSpeedColor(color: Int) {
        speedColor = color
        tvSpeed.setTextColor(color)
    }

    fun resetSpeedColor() {
        speedColor = palette.text
        tvSpeed.setTextColor(palette.text)
    }

    fun setTime(text: String) {
        tvTime.text = text
        tvExpTime.text = text
        requestMorphUpdate()
    }

    fun setBattery(percent: Int) {
        tvBattery.text = "$percent%"
        tvExpBattery.text = "Bat: $percent%"
        requestMorphUpdate()
    }

    fun setAccuracy(text: String, color: Int) {
        tvGpsAcc.text = text
        tvGpsAcc.setTextColor(color)
    }

    fun setSatellites(count: Int) {
        tvSatellites.text = "Sats: $count"
        tvSatellites.setTextColor(palette.sat)
    }

    fun setCompassAzimuth(azimuth: Float) {
        compass.azimuth = azimuth
    }

    fun setExtraStats(
        altitude: String,
        heading: String,
        fixAge: String,
        distance: String,
        average: String,
        maximum: String,
        coordinates: String
    ) {
        tvAltitude.text = altitude
        tvHeading.text = heading
        tvFix.text = fixAge
        tvTripDistance.text = distance
        tvTripAvg.text = average
        tvTripMax.text = maximum
        tvCoords.text = coordinates
    }

    fun setShowExtraStats(show: Boolean) {
        extraStats.visibility = if (show) VISIBLE else GONE
    }

    fun setRecording(recording: Boolean) {
        recordingVisual = recording
        btnRecord.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record)
        ImageViewCompat.setImageTintList(
            btnRecord,
            ColorStateList.valueOf(if (recording) palette.danger else palette.accent)
        )
        btnRecord.contentDescription = context.getString(if (recording) R.string.stop else R.string.record)
    }

    fun setSatellitesColor(color: Int) {
        tvSatellites.setTextColor(color)
    }

    fun pauseAutoCollapse() {
        autoCollapsePaused = true
        handler.removeCallbacks(autoCollapseRunnable)
    }

    fun resumeAutoCollapse() {
        autoCollapsePaused = false
        scheduleAutoCollapse()
    }

    fun expand() {
        handler.removeCallbacks(autoCollapseRunnable)
        animateTo(expandedY, 0f)
    }

    fun collapseToMini() {
        animateTo(miniY, 0f)
    }

    fun refreshMorph() {
        requestMorphUpdate()
    }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
        if (autoCollapseEnabled && !autoCollapsePaused && isExpanded) {
            handler.postDelayed(autoCollapseRunnable, 5000L)
        }
    }

    private fun requestMorphUpdate() {
        if (morphPosted) return
        morphPosted = true
        post {
            morphPosted = false
            if (width == 0) return@post
            computeMorph()
            applyMorph(translationY)
        }
    }

    private fun centerInPanel(view: View): FloatArray {
        var x = view.left + view.width / 2f
        var y = view.top + view.height / 2f
        var parent = view.parent
        while (parent is View && parent !== this) {
            x += parent.left
            y += parent.top
            parent = parent.parent
        }
        return floatArrayOf(x, y)
    }

    private fun computeMorph() {
        if (tvSpeed.width == 0 || tvExpSpeed.width == 0) return
        val speedStart = centerInPanel(tvSpeed)
        val speedEnd = centerInPanel(tvExpSpeed)
        morphDxSpeed = speedEnd[0] - speedStart[0]
        morphDySpeed = speedEnd[1] - speedStart[1]

        val timeStart = centerInPanel(tvTime)
        val timeEnd = centerInPanel(tvExpTime)
        morphDxTime = timeEnd[0] - timeStart[0]
        morphDyTime = timeEnd[1] - timeStart[1]

        val batteryStart = centerInPanel(tvBattery)
        val batteryEnd = centerInPanel(tvExpBattery)
        morphDxBattery = batteryEnd[0] - batteryStart[0]
        morphDyBattery = batteryEnd[1] - batteryStart[1]

        scaleSpeed = if (tvSpeed.textSize > 0f) tvExpSpeed.textSize / tvSpeed.textSize else 1f
        scaleTime = if (tvTime.textSize > 0f) tvExpTime.textSize / tvTime.textSize else 1f
        scaleBattery = if (tvBattery.textSize > 0f) tvExpBattery.textSize / tvBattery.textSize else 1f

        tvSpeed.pivotX = tvSpeed.width / 2f
        tvSpeed.pivotY = tvSpeed.height / 2f
        tvTime.pivotX = tvTime.width / 2f
        tvTime.pivotY = tvTime.height / 2f
        tvBattery.pivotX = tvBattery.width / 2f
        tvBattery.pivotY = tvBattery.height / 2f
    }

    private fun applyMorph(currentY: Float) {
        val p = ((currentY - miniY) / (expandedY - miniY)).coerceIn(0f, 1f)

        tvSpeed.translationX = morphDxSpeed * p
        tvSpeed.translationY = morphDySpeed * p
        tvSpeed.scaleX = 1f + (scaleSpeed - 1f) * p
        tvSpeed.scaleY = 1f + (scaleSpeed - 1f) * p

        tvTime.translationX = morphDxTime * p
        tvTime.translationY = morphDyTime * p
        tvTime.scaleX = 1f + (scaleTime - 1f) * p
        tvTime.scaleY = 1f + (scaleTime - 1f) * p

        tvBattery.translationX = morphDxBattery * p
        tvBattery.translationY = morphDyBattery * p
        tvBattery.scaleX = 1f + (scaleBattery - 1f) * p
        tvBattery.scaleY = 1f + (scaleBattery - 1f) * p

        expandedLayout.alpha = p
        expandedLayout.visibility = if (p < 0.008f) INVISIBLE else VISIBLE
        val clickable = p > 0.85f
        btnSettings.isEnabled = clickable
        btnResetTrip.isEnabled = clickable
        btnRecord.isEnabled = clickable
        btnTracks.isEnabled = clickable
        miniLayout.isClickable = p < 0.15f
        miniLayout.isFocusable = p < 0.15f
    }

    private fun animateTo(target: Float, startVelocity: Float) {
        spring?.cancel()
        handler.removeCallbacks(autoCollapseRunnable)
        val clampedTarget = target.coerceIn(hiddenY, expandedY)
        val animation = SpringAnimation(this, DynamicAnimation.TRANSLATION_Y).apply {
            this.spring = SpringForce(clampedTarget).apply {
                stiffness = 420f
                dampingRatio = 0.82f
            }
            setMinValue(hiddenY)
            setMaxValue(expandedY)
            if (abs(startVelocity) > 1f) setStartVelocity(startVelocity)
            addUpdateListener { _, value, _ -> applyMorph(value) }
            addEndListener { _, canceled, _, _ ->
                if (!canceled) {
                    translationY = clampedTarget
                    applyMorph(clampedTarget)
                    scheduleAutoCollapse()
                }
            }
        }
        spring = animation
        animation.start()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                spring?.cancel()
                handler.removeCallbacks(autoCollapseRunnable)
                downRawX = ev.rawX
                downRawY = ev.rawY
                downTranslation = translationY
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dy = ev.rawY - downRawY
                val dx = ev.rawX - downRawX
                if (!dragging && abs(dy) > touchSlop && abs(dy) > abs(dx) * 0.7f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (abs(translationY - expandedY) > 2f &&
                        abs(translationY - miniY) > 2f &&
                        abs(translationY - hiddenY) > 2f
                    ) {
                        animateTo(decideTarget(translationY, 0f), 0f)
                    } else {
                        scheduleAutoCollapse()
                    }
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                velocityTracker?.addMovement(event)
                val y = (downTranslation + (event.rawY - downRawY)).coerceIn(hiddenY, expandedY)
                translationY = y
                applyMorph(y)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocity = velocityTracker?.yVelocity ?: 0f
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                animateTo(decideTarget(translationY, velocity), velocity)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                animateTo(decideTarget(translationY, 0f), 0f)
                return true
            }
        }
        return dragging
    }

    private fun decideTarget(current: Float, velocity: Float): Float {
        val expandMid = (expandedY + miniY) / 2f
        val miniMid = (miniY + hiddenY) / 2f
        if (velocity > 800f) {
            return if (current > miniY + 12f) expandedY else miniY
        }
        if (velocity < -800f) {
            return if (current < miniY - 12f) hiddenY else miniY
        }
        return when {
            current > expandMid -> expandedY
            current > miniMid -> miniY
            else -> hiddenY
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized && h > 0) {
            initialized = true
            translationY = miniY
            post {
                computeMorph()
                applyMorph(miniY)
            }
        }
        requestMorphUpdate()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(autoCollapseRunnable)
        spring?.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }
}
