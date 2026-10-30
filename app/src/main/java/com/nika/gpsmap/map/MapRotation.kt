package com.nika.gpsmap.map

import android.view.Choreographer
import android.view.MotionEvent
import org.osmdroid.views.MapView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Frame-clock driven map rotation.
 *
 * The previous implementation pushed every sensor sample straight into
 * [MapView.setMapOrientation]. Sensor and touch events arrive at an uneven rate,
 * so the map visibly stepped, and osmdroid's own rotation overlay accumulated
 * its angle and applied it again every 25 ms, which made two-finger rotation
 * accelerate and zig-zag.
 *
 * Instead this class keeps a target orientation and eases the visible angle
 * towards it once per display frame. Two-finger gestures call [rotateBy], which
 * is applied immediately so the map stays glued to the fingers; heading-up
 * tracking calls [animateTo], which keeps the target moving at sensor rate but
 * repaints at display rate.
 */
class SmoothMapRotation(private val map: MapView) {

    private var current = normalize(map.mapOrientation)
    private var target = current
    private var applied = Float.NaN
    private var followTauSeconds = DEFAULT_TAU_SECONDS
    private var frameScheduled = false
    private var lastFrameNanos = 0L

    private val choreographer = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            val dt = if (lastFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            }
            lastFrameNanos = frameTimeNanos

            val remaining = shortestDelta(current, target)
            if (abs(remaining) <= SNAP_EPSILON) {
                current = target
                apply()
                lastFrameNanos = 0L
                return
            }

            // Exponential ease. Unlike a fixed per-sample blend it behaves the
            // same on 60 Hz and 90 Hz panels and does not depend on how often
            // the sensor happens to fire.
            val factor = 1f - exp(-dt / followTauSeconds)
            current = normalize(current + remaining * factor)
            apply()
            requestFrame()
        }
    }

    /** Starts easing towards [orientation] without an abrupt jump. */
    fun animateTo(
        orientation: Float,
        tauSeconds: Float = DEFAULT_TAU_SECONDS,
        deadBandDegrees: Float = 0f
    ) {
        val normalized = normalize(orientation)
        if (deadBandDegrees > 0f &&
            abs(shortestDelta(target, normalized)) < deadBandDegrees
        ) {
            return
        }
        followTauSeconds = tauSeconds.coerceIn(0.02f, 0.3f)
        target = normalized
        if (abs(shortestDelta(current, target)) <= SNAP_EPSILON) {
            current = target
            apply()
            return
        }
        requestFrame()
    }

    /**
     * Applies a gesture delta immediately. Rotation gestures already follow the
     * fingers, so any extra easing here would read as lag.
     */
    fun rotateBy(delta: Float) {
        if (abs(delta) < 0.001f) return
        current = normalize(current + delta)
        target = current
        apply()
    }

    /** Stops any easing and keeps the map exactly where it currently is. */
    fun cancelAnimation() {
        target = current
    }

    /** Jumps to an angle (used when restoring state, not during interaction). */
    fun snapTo(orientation: Float) {
        current = normalize(orientation)
        target = current
        apply()
    }

    /** Re-reads the map angle after something else changed it. */
    fun syncFromMap() {
        current = normalize(map.mapOrientation)
        target = current
        applied = current
    }

    fun stop() {
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        lastFrameNanos = 0L
        target = current
    }

    private fun requestFrame() {
        if (frameScheduled) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun apply() {
        val normalized = normalize(current)
        if (!applied.isNaN() && abs(shortestDelta(applied, normalized)) < APPLY_EPSILON) {
            return
        }
        applied = normalized
        // forceRedraw=false skips the requestLayout() that osmdroid normally
        // performs for an orientation change. The map has no child views that
        // depend on the projection, so invalidating is enough and avoids a full
        // layout pass on every animation frame.
        map.setMapOrientation(normalized, false)
        map.invalidate()
    }

    companion object {
        private const val DEFAULT_TAU_SECONDS = 0.055f
        private const val SNAP_EPSILON = 0.02f
        private const val APPLY_EPSILON = 0.015f

        private fun normalize(value: Float): Float {
            val wrapped = value % 360f
            return if (wrapped < 0f) wrapped + 360f else wrapped
        }

        private fun shortestDelta(from: Float, to: Float): Float {
            var delta = (to - from + 540f) % 360f - 180f
            if (delta < -180f) delta += 360f
            return delta
        }
    }
}

/**
 * Two-finger rotation gesture.
 *
 * This deliberately runs from the activity's raw OnTouchListener instead of an
 * osmdroid overlay. MapView un-rotates overlay touch coordinates by the current
 * map angle, which means an overlay sees its own previous rotation subtracted
 * from the next finger delta. That feedback made real two-finger rotation
 * stutter and flash between angles. Raw events are unaffected by the map
 * projection, so the angle here is always the physical finger angle.
 */
class SmoothRotationGesture(
    private val rotation: SmoothMapRotation,
    private val minSpanPx: Float
) {

    private var lastAngle = Float.NaN
    private var pendingDelta = 0f

    fun handle(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> reset()

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount < 2) {
                    reset()
                    return
                }
                val x0 = event.getX(0)
                val y0 = event.getY(0)
                val x1 = event.getX(1)
                val y1 = event.getY(1)
                if (hypot(x1 - x0, y1 - y0) < minSpanPx) {
                    reset()
                    return
                }
                val angle = Math.toDegrees(
                    atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                ).toFloat()
                if (lastAngle.isNaN()) {
                    lastAngle = angle
                    return
                }
                var delta = angle - lastAngle
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                lastAngle = angle

                // A single move should never report a near-half-turn; that is
                // always a pointer glitch or a very fast twist.
                if (abs(delta) > 40f) {
                    pendingDelta = 0f
                    return
                }
                pendingDelta += delta
                if (abs(pendingDelta) >= 0.12f) {
                    rotation.rotateBy(pendingDelta)
                    pendingDelta = 0f
                }
            }
        }
    }

    private fun reset() {
        lastAngle = Float.NaN
        pendingDelta = 0f
    }
}
