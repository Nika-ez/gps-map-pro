package com.nika.gpsmap.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.nika.gpsmap.Palette
import com.nika.gpsmap.R
import com.nika.gpsmap.ThemeMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Four-stage dashboard:
 *
 *   0 hidden  - only the handle remains
 *   1 mini    - time, speed, battery
 *   2 main    - compass, time, speed, battery
 *   3 full    - extra stats, satellites and the action row
 *
 * The panel height itself is animated, so the content is revealed rather than
 * sliding off-screen. The old bar still looks identical; only its touch target
 * and drag distance are friendlier.
 */
class HudPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val hiddenHeight = dp(30f)
    private val miniHeight = dp(110f)
    private var mainHeight = dp(178f)
    private var expandedHeight = dp(250f)

    private lateinit var expandedLayout: RelativeLayout
    private lateinit var miniLayout: RelativeLayout
    private lateinit var tvSpeed: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvExpSpeed: TextView
    private lateinit var tvExpTime: TextView
    private lateinit var tvExpBattery: TextView
    private lateinit var tvGpsAcc: TextView
    private lateinit var tvSatellites: TextView
    private lateinit var tvAltitude: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvFix: TextView
    private lateinit var tvTripDistance: TextView
    private lateinit var tvTripAvg: TextView
    private lateinit var tvTripMax: TextView
    private lateinit var tvCoords: TextView
    private lateinit var extraStats: LinearLayout
    private lateinit var actionRow: LinearLayout
    private lateinit var btnSettings: ImageButton
    private lateinit var btnResetTrip: ImageButton
    private lateinit var btnRecord: ImageButton
    private lateinit var btnTracks: ImageButton
    private lateinit var dragHandleArea: View
    private lateinit var dragBar: View
    private lateinit var compass: CompassView

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

    private var stageValue = STAGE_MINI
    private var heightConfigured = false
    private var viewsBound = false
    private var isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private var backgroundAlpha = 1f

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragStartHeight = 0f
    private var velocityTracker: VelocityTracker? = null
    private var spring: SpringAnimation? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var showExtraStatsEnabled = true
    private var recordingVisual = false
    private var autoCollapsePaused = false

    private var speedText = "km/h: (none)"
    private var timeText = "--:--"
    private var batteryPercentValue = -1
    private var accuracyText = "GPS Acc: --"
    private var accuracyColorValue = Color.GRAY
    private var satelliteCountValue = 0
    private var compassAzimuthValue = 0f
    private var extraValues = arrayOf("—", "—", "—", "0.00", "0.0", "0.0", "—")

    private val handler = Handler(Looper.getMainLooper())

    private val autoCollapseRunnable = Runnable {
        if (autoCollapseEnabled && !autoCollapsePaused && stageValue > 1.02f) {
            animateToStage(STAGE_MINI, 0f)
        }
    }

    var autoCollapseEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) handler.removeCallbacks(autoCollapseRunnable)
            else scheduleAutoCollapse()
        }

    /**
     * When true the dashboard cannot be dragged below the mini bar. This is the
     * "never hide dashboard" setting requested for 2.2.
     */
    var lockOpen: Boolean = false
        set(value) {
            field = value
            if (value && stageValue < STAGE_MINI) {
                animateToStage(STAGE_MINI, 0f)
            }
        }

    var onSettingsClick: (() -> Unit)? = null
    var onResetTripClick: (() -> Unit)? = null
    var onRecordClick: (() -> Unit)? = null
    var onTracksClick: (() -> Unit)? = null

    val currentStage: Float
        get() = stageValue

    val isExpanded: Boolean
        get() = stageValue > 1.5f

    val isRecordingButton: Boolean
        get() = recordingVisual

    init {
        clipChildren = true
        clipToPadding = true
        LayoutInflater.from(context).inflate(R.layout.hud_panel, this, true)
        bindViews()
        applyPalette(palette)
    }

    private fun bindViews() {
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

        // Shared texts morph into the expanded layout and must be able to draw
        // outside the short mini container without being clipped.
        miniLayout.clipChildren = false
        miniLayout.clipToPadding = false
        expandedLayout.clipChildren = false

        btnSettings.setOnClickListener { onSettingsClick?.invoke() }
        btnResetTrip.setOnClickListener { onResetTripClick?.invoke() }
        btnRecord.setOnClickListener { onRecordClick?.invoke() }
        btnTracks.setOnClickListener { onTracksClick?.invoke() }

        dragHandleArea.setOnClickListener {
            val current = nearestStage()
            val target = when (current) {
                0 -> 1f
                1 -> 2f
                2 -> 3f
                else -> 1f
            }
            animateToStage(target, 0f)
        }
        miniLayout.setOnClickListener {
            animateToStage(if (stageValue < 1.6f) 2f else 3f, 0f)
        }

        viewsBound = true
    }

    fun applyPalette(newPalette: Palette) {
        palette = newPalette
        speedColor = newPalette.text

        val radius = dp(32f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(newPalette.panel)
            cornerRadii = if (isLandscape) {
                floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            }
            alpha = (backgroundAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        }

        if (!viewsBound) return

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

    /**
     * Re-inflates the correct portrait/landscape content without recreating the
     * activity, so GPS, trip and recording state survive a rotation.
     */
    fun configureOrientation(landscape: Boolean) {
        if (viewsBound && landscape == isLandscape) return
        isLandscape = landscape
        heightConfigured = false
        dragging = false
        spring?.cancel()
        spring = null
        velocityTracker?.recycle()
        velocityTracker = null
        handler.removeCallbacks(autoCollapseRunnable)
        removeAllViews()
        LayoutInflater.from(context).inflate(R.layout.hud_panel, this, true)
        bindViews()
        applyPalette(palette)
        applySnapshot()
        applyExtraStatsVisibility()
        applyRecordingVisual()
        requestLayout()
    }

    /**
     * Re-applies the values that were last pushed into the view. Rotation
     * re-inflates the layout, so without this the dashboard would be blank until
     * the next GPS update.
     */
    private fun applySnapshot() {
        if (!viewsBound) return
        tvSpeed.text = speedText
        tvSpeed.setTextColor(speedColor)
        tvExpSpeed.text = speedText
        tvTime.text = timeText
        tvExpTime.text = timeText
        if (batteryPercentValue >= 0) {
            tvBattery.text = "$batteryPercentValue%"
            tvExpBattery.text = "Bat: $batteryPercentValue%"
        }
        tvGpsAcc.text = accuracyText
        tvGpsAcc.setTextColor(accuracyColorValue)
        tvSatellites.text = "Satellites: $satelliteCountValue"
        compass.azimuth = compassAzimuthValue
        tvAltitude.text = extraValues[0]
        tvHeading.text = extraValues[1]
        tvFix.text = extraValues[2]
        tvTripDistance.text = extraValues[3]
        tvTripAvg.text = extraValues[4]
        tvTripMax.text = extraValues[5]
        tvCoords.text = extraValues[6]
        tvSatellites.setTextColor(palette.sat)
    }

    fun setSpeed(text: String) {
        if (text == speedText) return
        speedText = text
        if (!viewsBound) return
        tvSpeed.text = text
        tvExpSpeed.text = text
        requestMorphUpdate()
    }

    fun setSpeedColor(color: Int) {
        if (color == speedColor) return
        speedColor = color
        if (viewsBound) tvSpeed.setTextColor(color)
    }

    fun resetSpeedColor() {
        speedColor = palette.text
        if (viewsBound) tvSpeed.setTextColor(palette.text)
    }

    fun setTime(text: String) {
        if (text == timeText) return
        timeText = text
        if (!viewsBound) return
        tvTime.text = text
        tvExpTime.text = text
        requestMorphUpdate()
    }

    fun setBattery(percent: Int) {
        if (percent == batteryPercentValue) return
        batteryPercentValue = percent
        if (!viewsBound) return
        tvBattery.text = "$percent%"
        tvExpBattery.text = "Bat: $percent%"
        requestMorphUpdate()
    }

    fun setAccuracy(text: String, color: Int) {
        if (text == accuracyText && color == accuracyColorValue) return
        accuracyText = text
        accuracyColorValue = color
        if (!viewsBound) return
        tvGpsAcc.text = text
        tvGpsAcc.setTextColor(color)
    }

    fun setSatellites(count: Int) {
        if (count == satelliteCountValue) return
        satelliteCountValue = count
        if (!viewsBound) return
        tvSatellites.text = "Satellites: $count"
        tvSatellites.setTextColor(palette.sat)
    }

    fun setCompassAzimuth(azimuth: Float) {
        val normalized = ((azimuth % 360f) + 360f) % 360f
        var difference = normalized - compassAzimuthValue
        if (difference > 180f) difference -= 360f
        if (difference < -180f) difference += 360f
        if (abs(difference) < 0.25f) return
        compassAzimuthValue = normalized
        if (viewsBound) compass.azimuth = normalized
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
        if (altitude == extraValues[0] &&
            heading == extraValues[1] &&
            fixAge == extraValues[2] &&
            distance == extraValues[3] &&
            average == extraValues[4] &&
            maximum == extraValues[5] &&
            coordinates == extraValues[6]
        ) {
            return
        }
        extraValues[0] = altitude
        extraValues[1] = heading
        extraValues[2] = fixAge
        extraValues[3] = distance
        extraValues[4] = average
        extraValues[5] = maximum
        extraValues[6] = coordinates
        if (!viewsBound) return
        tvAltitude.text = altitude
        tvHeading.text = heading
        tvFix.text = fixAge
        tvTripDistance.text = distance
        tvTripAvg.text = average
        tvTripMax.text = maximum
        tvCoords.text = coordinates
    }

    fun setShowExtraStats(show: Boolean) {
        showExtraStatsEnabled = show
        applyExtraStatsVisibility()
    }

    private fun applyExtraStatsVisibility() {
        if (!viewsBound) return
        extraStats.visibility = if (showExtraStatsEnabled) VISIBLE else GONE
    }

    fun setRecording(recording: Boolean) {
        recordingVisual = recording
        if (viewsBound) applyRecordingVisual()
    }

    private fun applyRecordingVisual() {
        btnRecord.setImageResource(if (recordingVisual) R.drawable.ic_stop else R.drawable.ic_record)
        ImageViewCompat.setImageTintList(
            btnRecord,
            ColorStateList.valueOf(if (recordingVisual) palette.danger else palette.accent)
        )
        btnRecord.contentDescription = context.getString(if (recordingVisual) R.string.stop else R.string.record)
    }

    fun setSatellitesColor(color: Int) {
        if (viewsBound) tvSatellites.setTextColor(color)
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
        animateToStage(STAGE_FULL, 0f)
    }

    fun collapseToMini() {
        animateToStage(STAGE_MINI, 0f)
    }

    fun refreshMorph() {
        requestMorphUpdate()
    }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
        if (autoCollapseEnabled && !autoCollapsePaused && stageValue > 1.02f) {
            handler.postDelayed(autoCollapseRunnable, 5000L)
        }
    }

    private fun requestMorphUpdate() {
        if (morphPosted) return
        morphPosted = true
        post {
            morphPosted = false
            if (width == 0 || !viewsBound) return@post
            computeMorph()
            applyMorph(miniToMainProgress(stageValue))
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

    private fun miniToMainProgress(stage: Float): Float =
        smoothStep((stage - STAGE_MINI).coerceIn(0f, 1f))

    private fun applyMorph(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
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
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun minStage(): Float = if (lockOpen) STAGE_MINI else STAGE_HIDDEN

    private fun nearestStage(): Int = stageValue.roundToInt().coerceIn(0, 3)

    private fun computeStageHeights() {
        val desiredMain = if (isLandscape) dp(190f) else dp(178f)
        val maximumMain = max(miniHeight, expandedHeight - dp(18f))
        mainHeight = min(desiredMain, maximumMain).coerceAtLeast(miniHeight)
    }

    private fun heightForStage(stage: Float): Float {
        val s = stage.coerceIn(STAGE_HIDDEN, STAGE_FULL)
        val safeMain = mainHeight.coerceIn(miniHeight, max(miniHeight, expandedHeight))
        return when {
            s <= 1f -> lerp(hiddenHeight, miniHeight, s)
            s <= 2f -> lerp(miniHeight, safeMain, s - 1f)
            else -> lerp(safeMain, max(safeMain, expandedHeight), (s - 2f).coerceAtMost(1f))
        }
    }

    private fun stageForHeight(height: Float): Float {
        val safeMain = mainHeight.coerceIn(miniHeight, max(miniHeight, expandedHeight))
        return when {
            height <= miniHeight -> {
                if (miniHeight <= hiddenHeight) 0f
                else ((height - hiddenHeight) / (miniHeight - hiddenHeight)).coerceIn(0f, 1f)
            }

            height <= safeMain -> {
                if (safeMain <= miniHeight) 1f
                else 1f + ((height - miniHeight) / (safeMain - miniHeight)).coerceIn(0f, 1f)
            }

            else -> {
                val full = max(safeMain, expandedHeight)
                if (full <= safeMain) 3f
                else 2f + ((height - safeMain) / (full - safeMain)).coerceIn(0f, 1f)
            }
        }
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction.coerceIn(0f, 1f)

    private fun applyStage(value: Float) {
        if (!viewsBound) return
        val clamped = value.coerceIn(minStage(), STAGE_FULL)
        stageValue = clamped

        val targetHeight = heightForStage(clamped).roundToInt()
        layoutParams?.let { params ->
            if (params.height != targetHeight) {
                params.height = targetHeight
                layoutParams = params
            }
        }

        // Like the original dashboard, the dark panel stays opaque even in the
        // hidden stage: only a slim strip with the drag handle is visible.
        backgroundAlpha = 1f
        (background as? GradientDrawable)?.alpha = (backgroundAlpha * 255f).roundToInt()

        // Mini content fades away as the panel collapses so the hidden stage
        // shows the handle by itself instead of text floating over the map.
        val miniAlpha = smoothStep(((clamped - 0.12f) / 0.72f).coerceIn(0f, 1f))
        miniLayout.alpha = miniAlpha
        miniLayout.visibility = if (miniAlpha < 0.008f) INVISIBLE else VISIBLE

        val expandedAlpha = smoothStep(((clamped - STAGE_MINI) / 0.9f).coerceIn(0f, 1f))
        expandedLayout.alpha = expandedAlpha
        expandedLayout.visibility = if (expandedAlpha < 0.008f) INVISIBLE else VISIBLE

        val extraAlpha = smoothStep(((clamped - 2.05f) / 0.85f).coerceIn(0f, 1f))
        tvGpsAcc.alpha = extraAlpha
        tvSatellites.alpha = extraAlpha
        actionRow.alpha = extraAlpha
        extraStats.alpha = if (showExtraStatsEnabled) extraAlpha else 0f

        val clickable = extraAlpha > 0.82f
        btnSettings.isEnabled = clickable
        btnResetTrip.isEnabled = clickable
        btnRecord.isEnabled = clickable
        btnTracks.isEnabled = clickable

        val miniClickable = clamped < 1.55f
        miniLayout.isClickable = miniClickable
        miniLayout.isFocusable = miniClickable

        applyMorph(miniToMainProgress(clamped))
    }

    private fun animateToStage(target: Float, startVelocity: Float) {
        spring?.cancel()
        handler.removeCallbacks(autoCollapseRunnable)
        val clamped = target.coerceIn(minStage(), STAGE_FULL)
        if (abs(stageValue - clamped) < 0.001f) {
            applyStage(clamped)
            scheduleAutoCollapse()
            return
        }
        if (!isAttachedToWindow || width == 0) {
            applyStage(clamped)
            scheduleAutoCollapse()
            return
        }
        val animation = SpringAnimation(this, STAGE_PROPERTY).apply {
            spring = SpringForce(clamped).apply {
                // A slow, readable glide. The old 240 stiffness (and even 170)
                // crossed a whole stage in ~150 ms, which read as a jump.
                stiffness = 90f
                dampingRatio = 0.9f
            }
            // Stage values span only 0..3, so the library default of 1f would
            // treat every move shorter than one whole stage as already finished
            // and jump there instantly. 0.0015 is under one pixel.
            setMinimumVisibleChange(0.0015f)
            setMinValue(minStage())
            setMaxValue(STAGE_FULL)
            if (abs(startVelocity) > 0.02f) setStartVelocity(startVelocity)
            addEndListener { _, canceled, _, _ ->
                if (!canceled) {
                    applyStage(clamped)
                    scheduleAutoCollapse()
                }
            }
        }
        spring = animation
        animation.start()
    }

    private fun prepareDrag(ev: MotionEvent) {
        spring?.cancel()
        handler.removeCallbacks(autoCollapseRunnable)
        downRawX = ev.rawX
        downRawY = ev.rawY
        dragStartHeight = heightForStage(stageValue)
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun pastDragSlop(ev: MotionEvent): Boolean {
        val dy = ev.rawY - downRawY
        val dx = ev.rawX - downRawX
        val dragSlop = max(2f, touchSlop * 0.45f)
        return abs(dy) > dragSlop && abs(dy) > abs(dx) * 0.6f
    }

    private fun applyDrag(ev: MotionEvent) {
        // The bar sits on the bottom edge of the panel, so the panel must grow
        // when the finger moves down: the handle then follows the finger
        // instead of running away from it.
        val rawHeight = dragStartHeight + (ev.rawY - downRawY)
        val minHeight = heightForStage(minStage())
        val maxHeight = max(minHeight, expandedHeight)
        applyStage(stageForHeight(rawHeight.coerceIn(minHeight, maxHeight)))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                prepareDrag(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (!dragging && pastDragSlop(ev)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    scheduleAutoCollapse()
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Touches on empty parts of the panel (for example the wide
                // hidden strip beside the handle) start a drag right here.
                prepareDrag(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!dragging) {
                    if (!pastDragSlop(event)) return true
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                applyDrag(event)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    scheduleAutoCollapse()
                    performClick()
                    return true
                }
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocity = velocityTracker?.yVelocity ?: 0f
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                animateToStage(snapTarget(velocity), stageVelocity(velocity))
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    scheduleAutoCollapse()
                    return true
                }
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                animateToStage(snapTarget(0f), 0f)
                return true
            }
        }
        return dragging
    }

    private fun stageVelocity(velocityPxPerSecond: Float): Float {
        val perStage = max(1f, (expandedHeight - hiddenHeight) / STAGE_FULL)
        // Dragging down grows the panel, so a positive screen velocity moves
        // the stage forward.
        return velocityPxPerSecond / perStage
    }

    private fun snapTarget(velocityPxPerSecond: Float): Float {
        val velocity = stageVelocity(velocityPxPerSecond)
        val target = when {
            velocity > 0.85f -> floor(stageValue + 0.01f) + 1f
            velocity < -0.85f -> ceil(stageValue - 0.01f) - 1f
            else -> stageValue.roundToInt().toFloat()
        }
        return target.coerceIn(minStage(), STAGE_FULL)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!heightConfigured && h > 0) {
            expandedHeight = h.toFloat()
            computeStageHeights()
            heightConfigured = true
            applyStage(stageValue)
            post { requestMorphUpdate() }
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(autoCollapseRunnable)
        spring?.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val STAGE_HIDDEN = 0f
        const val STAGE_MINI = 1f
        const val STAGE_FULL = 3f

        val STAGE_PROPERTY = object : FloatPropertyCompat<HudPanelView>("dashboardStage") {
            override fun getValue(view: HudPanelView): Float = view.stageValue

            override fun setValue(view: HudPanelView, value: Float) {
                view.applyStage(value)
            }
        }
    }
}
