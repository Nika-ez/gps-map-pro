package com.nika.gpsmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.nika.gpsmap.data.TrackPoint
import com.nika.gpsmap.data.TrackRecorder
import com.nika.gpsmap.data.TrackStore
import com.nika.gpsmap.data.TrackSummary
import com.nika.gpsmap.data.TripComputer
import com.nika.gpsmap.map.GeoSimplify
import com.nika.gpsmap.map.LocationMarkerOverlay
import com.nika.gpsmap.map.MapStyles
import com.nika.gpsmap.map.PinOverlay
import com.nika.gpsmap.map.RouteOverlay
import com.nika.gpsmap.map.SmoothMapRotation
import com.nika.gpsmap.map.SmoothRotationGesture
import com.nika.gpsmap.net.PlaceResult
import com.nika.gpsmap.ui.HudPanelView
import com.nika.gpsmap.ui.LayersSheet
import com.nika.gpsmap.ui.PinSheet
import com.nika.gpsmap.ui.RouteSheet
import com.nika.gpsmap.ui.RouteTarget
import com.nika.gpsmap.ui.SearchSheet
import com.nika.gpsmap.ui.SettingsSheet
import com.nika.gpsmap.ui.TracksSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.exp
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class FollowMode { OFF, LOCATION, HEADING }

    private lateinit var prefs: Prefs
    private lateinit var palette: Palette
    private lateinit var map: MapView
    private lateinit var hud: HudPanelView
    private lateinit var btnFollow: FloatingActionButton
    private lateinit var btnCameraMode: MaterialButton
    private lateinit var recordingPill: TextView
    private lateinit var attribution: TextView
    private lateinit var routeStatus: TextView
    private lateinit var trackPill: TextView
    private lateinit var markerOverlay: LocationMarkerOverlay
    private lateinit var pinOverlay: PinOverlay
    private lateinit var routeOverlay: RouteOverlay
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var rotationController: SmoothMapRotation
    private lateinit var rotationGesture: SmoothRotationGesture

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trip = TripComputer()
    private val recorder = TrackRecorder()

    private var lastLocation: Location? = null
    private var gpsFix = false
    private var lastFixRealtime = 0L
    private var followMode = FollowMode.OFF
    private val following: Boolean
        get() = followMode != FollowMode.OFF
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastRouteStatusText: String? = null
    private var batteryPercent = 0
    private var lastBatteryShown = -1
    private var lastTimeShown = ""
    private var flashPhase = false
    private var permissionDialogShown = false
    private var locationOffDialogShown = false
    private var recording = false
    private var currentPinLatitude = Double.NaN
    private var currentPinLongitude = Double.NaN
    private var currentPinLabel: String? = null
    private var displayedTrack: File? = null
    private var trackLine: Polyline? = null
    private var routePath: List<GeoPoint> = emptyList()
    private var routePathCumulative = DoubleArray(0)
    private var routePathTotalMeters = 0.0
    private var progressLocationTime = Long.MIN_VALUE
    private var progressCache: Pair<Double, Double>? = null
    private var batteryReceiverRegistered = false
    private var gnssRegistered = false

    private var rotationVectorSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private val rotationVectorMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val accelerometer = FloatArray(3)
    private val magnetometer = FloatArray(3)
    private val accelMagMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var hasAccelerometer = false
    private var hasMagnetometer = false
    private var compassReady = false
    private var smoothAzimuth = 0f
    private var magneticDeclination = 0f
    private var lastAzimuthSampleNanos = 0L
    private var lastCompassHudNanos = 0L
    private var displayRotation = 0

    private var settingsSheet: SettingsSheet? = null
    private var searchSheet: SearchSheet? = null
    private var layersSheet: LayersSheet? = null
    private var tracksSheet: TracksSheet? = null
    private var pinSheet: PinSheet? = null
    private var routeSheet: RouteSheet? = null

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startLocationUpdates()
            checkLocationEnabled()
        } else {
            toast(R.string.permission_denied)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            hud.setSatellites(status.satelliteCount)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
                Sensor.TYPE_ACCELEROMETER -> {
                    lowPass(event.values, accelerometer, 0.18f)
                    hasAccelerometer = true
                    if (rotationVectorSensor == null) handleAccelMag()
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    lowPass(event.values, magnetometer, 0.18f)
                    hasMagnetometer = true
                    if (rotationVectorSensor == null) handleAccelMag()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * The fused rotation vector already contains the accelerometer and
     * magnetometer data. It is both steadier and cheaper than combining the two
     * raw sensors at UI rate, so it is preferred whenever the phone has one.
     */
    private fun handleRotationVector(event: SensorEvent) {
        if (event.values.size < 3) return
        SensorManager.getRotationMatrixFromVector(rotationVectorMatrix, event.values)
        publishAzimuth(rotationVectorMatrix, SENSOR_TAU_SECONDS)
    }

    /** Fallback for phones without a rotation vector sensor. */
    private fun handleAccelMag() {
        if (!hasAccelerometer || !hasMagnetometer) return
        if (!SensorManager.getRotationMatrix(
                accelMagMatrix,
                null,
                accelerometer,
                magnetometer
            )
        ) {
            return
        }
        publishAzimuth(accelMagMatrix, FALLBACK_TAU_SECONDS)
    }

    private fun publishAzimuth(matrix: FloatArray, tauSeconds: Float) {
        val axisX: Int
        val axisY: Int
        when (displayRotation) {
            android.view.Surface.ROTATION_90 -> {
                axisX = SensorManager.AXIS_Y
                axisY = SensorManager.AXIS_MINUS_X
            }

            android.view.Surface.ROTATION_180 -> {
                axisX = SensorManager.AXIS_MINUS_X
                axisY = SensorManager.AXIS_MINUS_Y
            }

            android.view.Surface.ROTATION_270 -> {
                axisX = SensorManager.AXIS_MINUS_Y
                axisY = SensorManager.AXIS_X
            }

            else -> {
                axisX = SensorManager.AXIS_X
                axisY = SensorManager.AXIS_Y
            }
        }
        val remapped = SensorManager.remapCoordinateSystem(
            matrix,
            axisX,
            axisY,
            remappedMatrix
        )
        val source = if (remapped) remappedMatrix else matrix
        SensorManager.getOrientation(source, orientationAngles)
        val azimuth = normalizeDegrees(
            Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        )

        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastAzimuthSampleNanos == 0L) {
            1f / 50f
        } else {
            ((now - lastAzimuthSampleNanos) / 1_000_000_000f).coerceIn(0.001f, 0.25f)
        }
        lastAzimuthSampleNanos = now

        smoothAzimuth = if (!compassReady) {
            azimuth
        } else {
            blendAngle(smoothAzimuth, azimuth, 1f - exp(-dt / tauSeconds))
        }
        compassReady = true

        // The compass is a small view, 30 Hz is plenty; anything faster only
        // invalidates it without a visible difference.
        if (now - lastCompassHudNanos >= COMPASS_HUD_INTERVAL_NANOS) {
            lastCompassHudNanos = now
            hud.setCompassAzimuth(smoothAzimuth)
        }
        if (followMode == FollowMode.HEADING) applyHeadingOrientation()
    }

    private val uiTicker = object : Runnable {
        override fun run() {
            updateClockAndBattery()
            val now = SystemClock.elapsedRealtime()
            if (gpsFix && lastFixRealtime > 0L && now - lastFixRealtime > 15_000L) {
                setGpsFix(false)
            }
            updateStats()
            updateRouteStatus()
            if (recording) {
                recordingPill.visibility = android.view.View.VISIBLE
                recordingPill.text = getString(
                    R.string.recording_pill,
                    Fmt.duration(recorder.currentDurationMillis())
                )
            } else {
                recordingPill.visibility = android.view.View.GONE
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    private val flashRunnable = object : Runnable {
        override fun run() {
            if (!gpsFix) {
                flashPhase = !flashPhase
                updateSpeedNow()
            }
            mainHandler.postDelayed(this, 800L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) {
                batteryPercent = (level * 100 / scale).coerceIn(0, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        setTheme(prefs.theme.styleRes)
        super.onCreate(savedInstanceState)
        applyRequestedOrientation()
        MapStyles.configure(this)
        palette = Palette.from(this, prefs.theme)
        window.statusBarColor = palette.statusBar
        window.navigationBarColor = palette.statusBar

        setContentView(R.layout.activity_main)
        enableImmersiveMode()
        map = findViewById(R.id.map)
        hud = findViewById(R.id.hudPanel)
        btnFollow = findViewById(R.id.btnFollow)
        btnCameraMode = findViewById(R.id.btnCameraMode)
        recordingPill = findViewById(R.id.recordingPill)
        attribution = findViewById(R.id.attribution)
        routeStatus = findViewById(R.id.routeStatus)
        trackPill = findViewById(R.id.trackPill)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        updateDisplayRotation()

        applyOrientationLayout(isLandscape())
        setupMap()
        setupHud()
        setupControls()
        restoreMapPosition()
        restoreTrackRoute()
        updateCameraModeUi()
        updateFollowUi()
        updateAttribution()
        applyKeepScreenOn()

        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        batteryPercent = batteryManager
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .coerceIn(0, 100)

        hud.setAccuracy("GPS Acc: --", palette.subtle)
        updateSpeedNow()
        updateStats()
        updateClockAndBattery()

        if (hasLocationPermission()) {
            startLocationUpdates()
            checkLocationEnabled()
        } else if (savedInstanceState == null) {
            showPermissionRationale()
        }

    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        applyKeepScreenOn()
        if (::rotationController.isInitialized) rotationController.syncFromMap()
        updateDisplayRotation()
        startSensors()
        registerBatteryReceiver()
        mainHandler.removeCallbacks(uiTicker)
        mainHandler.post(uiTicker)
        mainHandler.removeCallbacks(flashRunnable)
        mainHandler.postDelayed(flashRunnable, 800L)
        if (hasLocationPermission()) {
            startLocationUpdates()
            checkLocationEnabled()
        } else if (!permissionDialogShown) {
            showPermissionRationale()
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(uiTicker)
        mainHandler.removeCallbacks(flashRunnable)
        stopLocationUpdates()
        stopSensors()
        if (::rotationController.isInitialized) rotationController.stop()
        unregisterBatteryReceiver()
        saveMapPosition()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateDisplayRotation()
        applyOrientationLayout(landscape)
        hud.configureOrientation(landscape)
        // Rotation can bring the status bar back for a moment; hide it again.
        enableImmersiveMode()
        updateAttribution()
        if (followMode == FollowMode.HEADING) applyHeadingOrientation()
    }

    @Suppress("DEPRECATION")
    private fun updateDisplayRotation() {
        displayRotation = windowManager.defaultDisplay.rotation
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Fullscreen like the original app: the phone status bar is hidden and can
     * only be revealed temporarily by swiping from the top edge.
     */
    private fun enableImmersiveMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        // Bottom sheets and dialogs briefly pull the status bar back in. Hide
        // it once more after the dialog window has finished going away.
        window.decorView.postDelayed({ hideStatusBar() }, 280L)
    }

    private fun hideStatusBar() {
        if (isFinishing || isDestroyed) return
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.statusBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun applyRequestedOrientation() {
        val requested = when (prefs.orientation) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (requestedOrientation != requested) {
            requestedOrientation = requested
        }
    }

    /**
     * Portrait keeps the full-width top panel. Landscape turns the dashboard
     * into a narrow left rail so the map keeps the rest of the screen.
     */
    private fun applyOrientationLayout(landscape: Boolean) {
        // Portrait: let the dashboard fill the top edge behind the hidden status
        // bar, otherwise the decor leaves a black status-bar-sized gap above it.
        // Landscape: keep the decor inside the display cutout so the rail does
        // not slide under the notch on the left edge.
        val decor = window.decorView
        decor.systemUiVisibility = if (landscape) {
            decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv()
        } else {
            decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        val hudParams = hud.layoutParams as FrameLayout.LayoutParams
        if (landscape) {
            hudParams.width = dp(250)
            hudParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            hudParams.gravity = Gravity.START or Gravity.TOP
            // Anchor the rail to the edge instead of letting it float as a
            // rounded card with a gap around it.
            hudParams.setMargins(0, 0, 0, 0)
        } else {
            hudParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            hudParams.height = dp(250)
            hudParams.gravity = Gravity.TOP
            hudParams.setMargins(0, 0, 0, 0)
        }
        hud.layoutParams = hudParams

        val pillParams = recordingPill.layoutParams as FrameLayout.LayoutParams
        val attributionParams = attribution.layoutParams as FrameLayout.LayoutParams
        if (landscape) {
            pillParams.gravity = Gravity.BOTTOM or Gravity.END
            pillParams.setMargins(0, 0, dp(100), dp(52))
            attributionParams.gravity = Gravity.BOTTOM or Gravity.END
            attributionParams.setMargins(0, 0, dp(100), dp(16))
        } else {
            pillParams.gravity = Gravity.BOTTOM or Gravity.START
            pillParams.setMargins(dp(20), 0, 0, dp(52))
            attributionParams.gravity = Gravity.BOTTOM or Gravity.START
            attributionParams.setMargins(dp(20), 0, 0, dp(16))
        }
        recordingPill.layoutParams = pillParams
        attribution.layoutParams = attributionParams
    }

    private fun updateAttribution() {
        attribution.text = getString(
            if (prefs.theme == ThemeMode.ORIGINAL) R.string.attribution_osm else R.string.attribution
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun setupMap() {
        map.setMultiTouchControls(true)
        MapStyles.apply(map, prefs.theme, prefs.offlineOnly)
        map.controller.setZoom(18.0)
        map.controller.setCenter(GeoPoint(48.2082, 16.3738))

        pinOverlay = PinOverlay().apply { setPalette(palette.accent) }
        markerOverlay = LocationMarkerOverlay().apply { setStyle(prefs.markerStyle) }
        routeOverlay = RouteOverlay().apply {
            setStart(prefs.routeStart)
            setDestination(prefs.routeDestination)
        }
        map.overlays.add(pinOverlay)
        map.overlays.add(routeOverlay)
        map.overlays.add(markerOverlay)
        map.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

                override fun longPressHelper(p: GeoPoint?): Boolean {
                    if (p != null) dropPin(p)
                    return true
                }
            })
        )
        rotationController = SmoothMapRotation(map)
        rotationGesture = SmoothRotationGesture(
            rotationController,
            72f * resources.displayMetrics.density
        )

        map.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) stopFollowingForGesture()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (following) {
                        // A one-finger drag pans the map; stop camera tracking
                        // so the next GPS fix does not yank the map back.
                        val dx = event.x - touchDownX
                        val dy = event.y - touchDownY
                        val slop = ViewConfiguration.get(this).scaledTouchSlop
                        if (dx * dx + dy * dy > slop.toFloat() * slop) {
                            stopFollowingForGesture()
                        }
                    }
                }
            }
            if (!following) rotationGesture.handle(event)
            false
        }
    }

    private fun setupHud() {
        hud.applyPalette(palette)
        hud.autoCollapseEnabled = prefs.autoCollapse
        hud.lockOpen = prefs.keepDashboardVisible
        hud.setShowExtraStats(prefs.showExtraStats)
        hud.onSettingsClick = { openSettings() }
        hud.onResetTripClick = {
            trip.reset()
            updateStats()
        }
        hud.onRecordClick = { toggleRecording() }
        hud.onTracksClick = { openTracks() }
    }

    private fun setupControls() {
        findViewById<FloatingActionButton>(R.id.btnRoute).setOnClickListener { openRouteSheet() }
        findViewById<FloatingActionButton>(R.id.btnSearch).setOnClickListener { openSearch() }
        findViewById<FloatingActionButton>(R.id.btnLayers).setOnClickListener { openLayers() }
        routeStatus.setOnClickListener { openRouteSheet() }
        trackPill.setOnClickListener {
            if (displayedTrack != null) {
                clearTrackLine()
                toast(R.string.track_hidden)
            }
        }
        btnFollow.setOnClickListener {
            if (!following && !hasLocationPermission()) {
                requestLocationPermissionNow()
                return@setOnClickListener
            }
            cycleFollowMode()
        }
        btnCameraMode.setOnClickListener { snapMapNorth() }
    }

    private fun setupSensors() {
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    private fun startSensors() {
        if (rotationVectorSensor == null && accelSensor == null && magSensor == null) {
            setupSensors()
        }
        lastAzimuthSampleNanos = 0L
        val rotationVector = rotationVectorSensor
        if (rotationVector != null) {
            sensorManager.registerListener(
                sensorListener,
                rotationVector,
                SensorManager.SENSOR_DELAY_GAME
            )
            return
        }
        // Fallback path only: no fused rotation vector on this phone.
        accelSensor?.let {
            sensorManager.registerListener(
                sensorListener,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        magSensor?.let {
            sensorManager.registerListener(
                sensorListener,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(sensorListener)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val providers = ArrayList<String>(2)
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            // GPS only when available: mixing in coarse network fixes was the
            // source of the 100 m accuracy jumps and the zig-zag tracks.
            providers.add(LocationManager.GPS_PROVIDER)
        } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providers.add(LocationManager.NETWORK_PROVIDER)
        }
        providers.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    1000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        }
        if (providers.contains(LocationManager.GPS_PROVIDER)) {
            registerGnss()
        }
    }

    private fun stopLocationUpdates() {
        runCatching { locationManager.removeUpdates(locationListener) }
        unregisterGnss()
    }

    @SuppressLint("MissingPermission")
    private fun registerGnss() {
        if (gnssRegistered) return
        runCatching {
            locationManager.registerGnssStatusCallback(
                gnssCallback,
                Handler(Looper.getMainLooper())
            )
            gnssRegistered = true
        }
    }

    private fun unregisterGnss() {
        if (!gnssRegistered) return
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        gnssRegistered = false
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionRationale() {
        if (permissionDialogShown || isFinishing) return
        permissionDialogShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_rationale_body)
            .setNegativeButton(R.string.not_now, null)
            .setPositiveButton(R.string.grant) { _, _ ->
                requestLocationPermissionNow()
            }
            .show()
    }

    private fun requestLocationPermissionNow() {
        requestLocationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun checkLocationEnabled() {
        if (!hasLocationPermission() || locationOffDialogShown) return
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (enabled) return
        locationOffDialogShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_disabled_title)
            .setMessage(R.string.location_services_off)
            .setNegativeButton(R.string.not_now, null)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            .show()
    }

    private fun handleLocation(location: Location) {
        val copy = Location(location)
        val previous = lastLocation
        val wasMissing = !gpsFix
        lastLocation = copy
        lastFixRealtime = SystemClock.elapsedRealtime()
        trip.onLocation(copy, lastFixRealtime)
        recorder.add(copy)
        setGpsFix(true)

        runCatching {
            magneticDeclination = GeomagneticField(
                copy.latitude.toFloat(),
                copy.longitude.toFloat(),
                if (copy.hasAltitude()) copy.altitude.toFloat() else 0f,
                System.currentTimeMillis()
            ).declination
        }

        val heading = when {
            compassReady -> smoothAzimuth
            copy.hasBearing() -> copy.bearing
            else -> null
        }
        markerOverlay.update(copy.latitude, copy.longitude, copy.accuracy, heading, true)

        if (following) {
            val zoom = if (map.zoomLevelDouble < 13.0) 16.5 else map.zoomLevelDouble
            val target = GeoPoint(copy.latitude, copy.longitude)
            if (wasMissing) {
                map.controller.animateTo(target, zoom, 650L)
            } else {
                // GPS arrives once per second. Gliding between fixes keeps
                // heading-up tracking smooth instead of letting the map jump
                // one fix at a time. Small movements (standing still, GPS
                // jitter) are applied directly so the map does not animate
                // forever while the phone is on a table.
                val movedPixels = previous?.let {
                    map.projection.metersToPixels(it.distanceTo(copy))
                } ?: Float.MAX_VALUE
                if (movedPixels > 24f) {
                    map.controller.animateTo(target, zoom, 850L)
                } else {
                    map.controller.setCenter(target)
                }
            }
            applyHeadingOrientation()
        }
        map.invalidate()
        updateSpeedNow()
        updateStats()
    }

    private fun setGpsFix(fix: Boolean) {
        if (gpsFix == fix) return
        gpsFix = fix
        if (!fix) {
            markerOverlay.clear()
            map.invalidate()
            flashPhase = false
        }
        updateSpeedNow()
        updateStats()
    }

    private fun updateSpeedNow() {
        val location = lastLocation
        if (gpsFix && location != null) {
            val speed = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else 0f
            hud.setSpeed(Fmt.speedWithUnit(speed, prefs.units))
            hud.resetSpeedColor()
        } else if (flashPhase) {
            hud.setSpeed(getString(R.string.status_no_gps))
            hud.setSpeedColor(
                if (prefs.theme == ThemeMode.ORIGINAL) Color.RED else palette.danger
            )
        } else {
            val unit = if (prefs.units == UnitSystem.METRIC) "km/h" else "mph"
            hud.setSpeed("$unit: (none)")
            hud.setSpeedColor(palette.text)
        }
    }

    private fun updateStats() {
        val location = lastLocation
        val noValue = getString(R.string.no_value)
        if (gpsFix && location != null) {
            val accuracy = location.accuracy
            val accuracyColor = when {
                accuracy < 10f -> if (prefs.theme == ThemeMode.ORIGINAL) Color.GREEN else palette.accent
                accuracy < 20f -> if (prefs.theme == ThemeMode.ORIGINAL) Color.YELLOW else palette.warn
                else -> if (prefs.theme == ThemeMode.ORIGINAL) Color.RED else palette.danger
            }
            hud.setAccuracy(Fmt.accuracy(accuracy), accuracyColor)

            val heading = when {
                compassReady -> smoothAzimuth
                location.hasBearing() -> location.bearing
                else -> 0f
            }
            val altitude = if (location.hasAltitude()) location.altitude else 0.0
            hud.setExtraStats(
                Fmt.altitude(altitude, prefs.units),
                Fmt.heading(normalizeDegrees(heading)),
                Fmt.fixAge(SystemClock.elapsedRealtime() - lastFixRealtime),
                Fmt.distance(trip.distanceMeters, prefs.units),
                Fmt.speedValue(trip.averageSpeedMps(), prefs.units),
                Fmt.speedValue(trip.maxSpeedMps, prefs.units),
                Fmt.coordinates(location.latitude, location.longitude, prefs.coordDms)
            )
        } else {
            hud.setAccuracy("GPS Acc: --", palette.subtle)
            hud.setExtraStats(
                noValue,
                noValue,
                noValue,
                Fmt.distance(trip.distanceMeters, prefs.units),
                Fmt.speedValue(trip.averageSpeedMps(), prefs.units),
                Fmt.speedValue(trip.maxSpeedMps, prefs.units),
                if (location != null) {
                    Fmt.coordinates(location.latitude, location.longitude, prefs.coordDms)
                } else {
                    noValue
                }
            )
        }
    }

    private fun updateClockAndBattery() {
        val time = Fmt.clock(System.currentTimeMillis())
        if (time != lastTimeShown) {
            lastTimeShown = time
            hud.setTime(time)
        }
        if (batteryPercent != lastBatteryShown) {
            lastBatteryShown = batteryPercent
            hud.setBattery(batteryPercent)
        }
    }

    /**
     * Live straight-line route status: distance left, ETA from the current (or
     * trip average) speed and the percentage of the direct route already covered.
     */
    private fun updateRouteStatus() {
        val destination = prefs.routeDestination
        val trackRoute = routePath.size >= 2
        if (destination == null && !trackRoute) {
            routeStatus.visibility = android.view.View.GONE
            routeSheet?.updateLiveStatus(null)
            return
        }

        val start = prefs.routeStart
        val totalMeters = when {
            trackRoute -> routePathTotalMeters
            start != null && destination != null -> distanceMeters(
                start.latitude,
                start.longitude,
                destination.latitude,
                destination.longitude
            )

            else -> null
        }
        val totalLabel = totalMeters?.let { Fmt.distance(it, prefs.units) }
        val location = lastLocation

        if (!gpsFix || location == null) {
            if (totalLabel != null) {
                setRouteStatusText(getString(R.string.route_progress_no_fix, totalLabel))
                routeSheet?.updateLiveStatus(getString(R.string.route_live_no_fix, totalLabel))
            } else {
                routeStatus.visibility = android.view.View.GONE
                routeSheet?.updateLiveStatus(null)
            }
            return
        }

        val trackProgress = if (trackRoute) routeProgress(location) else null
        val remainingMeters = trackProgress?.second ?: if (destination != null) {
            distanceMeters(
                location.latitude,
                location.longitude,
                destination.latitude,
                destination.longitude
            )
        } else {
            0.0
        }
        val remainingLabel = Fmt.distance(remainingMeters, prefs.units)
        val progress = if (totalMeters != null && totalMeters > 1.0) {
            (((totalMeters - remainingMeters) / totalMeters) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        } else {
            null
        }

        val currentSpeed = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else 0f
        val effectiveSpeed = if (currentSpeed > 0.5f) currentSpeed else trip.averageSpeedMps()
        val etaMillis = if (effectiveSpeed > 0.5f) {
            ((remainingMeters / effectiveSpeed) * 1000.0).toLong()
        } else {
            null
        }
        val etaLabel = etaMillis?.let { Fmt.eta(it) }

        val statusText = when {
            etaLabel != null && progress != null ->
                getString(R.string.route_progress, remainingLabel, etaLabel, progress)

            etaLabel != null ->
                getString(R.string.route_progress_eta, remainingLabel, etaLabel)

            progress != null ->
                getString(R.string.route_progress_no_eta, remainingLabel, progress)

            else -> getString(R.string.route_progress_unknown, remainingLabel)
        }
        setRouteStatusText(statusText)

        val live = when {
            progress != null && etaLabel != null ->
                getString(R.string.route_live, remainingLabel, etaLabel, progress)

            progress != null ->
                getString(R.string.route_live_no_eta, remainingLabel, progress)

            etaLabel != null ->
                getString(R.string.route_live_no_start, remainingLabel, etaLabel)

            else -> null
        }
        routeSheet?.updateLiveStatus(live)
    }

    private fun setRouteStatusText(text: String) {
        if (lastRouteStatusText != text) {
            lastRouteStatusText = text
            routeStatus.text = text
        }
        if (routeStatus.visibility != View.VISIBLE) {
            routeStatus.visibility = View.VISIBLE
        }
    }

    private fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double
    ): Double {
        val result = FloatArray(1)
        Location.distanceBetween(fromLatitude, fromLongitude, toLatitude, toLongitude, result)
        return result[0].toDouble()
    }

    private fun setRoutePath(
        points: List<GeoPoint>,
        displayPoints: List<GeoPoint> = points
    ) {
        progressLocationTime = Long.MIN_VALUE
        progressCache = null
        if (points.size < 2) {
            routePath = emptyList()
            routePathCumulative = DoubleArray(0)
            routePathTotalMeters = 0.0
            routeOverlay.setPath(emptyList())
            return
        }
        val cumulative = DoubleArray(points.size)
        var total = 0.0
        for (index in 1 until points.size) {
            total += pathDistanceMeters(points[index - 1], points[index])
            cumulative[index] = total
        }
        routePath = points
        routePathCumulative = cumulative
        routePathTotalMeters = total
        routeOverlay.setPath(displayPoints)
    }

    private fun pathDistanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val latitude = Math.toRadians((a.latitude + b.latitude) * 0.5)
        val dx = (b.longitude - a.longitude) * 111_320.0 * Math.cos(latitude)
        val dy = (b.latitude - a.latitude) * 110_540.0
        return Math.hypot(dx, dy)
    }

    private fun clearRoutePath() {
        routePath = emptyList()
        routePathCumulative = DoubleArray(0)
        routePathTotalMeters = 0.0
        progressLocationTime = Long.MIN_VALUE
        progressCache = null
        routeOverlay.setPath(emptyList())
        prefs.routeTrackPath = null
        prefs.routeTrackName = null
        prefs.routeTrackDistanceMeters = 0.0
    }

    /**
     * Projects the current position onto the active track and returns the
     * distance already covered and the distance that is still left.
     * The result is cached per GPS fix so the 500 ms UI tick does not rescan
     * a long track every time.
     */
    private fun routeProgress(location: Location): Pair<Double, Double>? {
        val path = routePath
        if (path.size < 2 || routePathCumulative.size != path.size) return null
        if (location.time == progressLocationTime) return progressCache
        val lat = location.latitude
        val lon = location.longitude
        val metersPerDegreeLat = 110_540.0
        val metersPerDegreeLon = 111_320.0 * Math.cos(Math.toRadians(lat))
        var bestDistanceSquared = Double.MAX_VALUE
        var bestCovered = 0.0
        for (index in 0 until path.size - 1) {
            val ax = (path[index].longitude - lon) * metersPerDegreeLon
            val ay = (path[index].latitude - lat) * metersPerDegreeLat
            val bx = (path[index + 1].longitude - lon) * metersPerDegreeLon
            val by = (path[index + 1].latitude - lat) * metersPerDegreeLat
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared <= 0.0) 0.0
            else ((-(ax * dx + ay * dy)) / lengthSquared).coerceIn(0.0, 1.0)
            val px = ax + dx * t
            val py = ay + dy * t
            val distanceSquared = px * px + py * py
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared
                val segmentLength = routePathCumulative[index + 1] - routePathCumulative[index]
                bestCovered = routePathCumulative[index] + segmentLength * t
            }
        }
        val covered = bestCovered.coerceIn(0.0, routePathTotalMeters)
        val result = Pair(covered, (routePathTotalMeters - covered).coerceAtLeast(0.0))
        progressLocationTime = location.time
        progressCache = result
        return result
    }

    private fun restoreTrackRoute() {
        val saved = prefs.routeTrackPath ?: return
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val points = runCatching { TrackStore.load(File(saved)) }.getOrDefault(emptyList())
                val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
                geoPoints to GeoSimplify.simplifyForDisplay(geoPoints)
            }
            val points = loaded.first
            if (points.size < 2) {
                prefs.routeTrackPath = null
                prefs.routeTrackName = null
                prefs.routeTrackDistanceMeters = 0.0
                return@launch
            }
            setRoutePath(points, loaded.second)
            prefs.routeTrackDistanceMeters = routePathTotalMeters
            map.invalidate()
            updateRouteStatus()
        }
    }

    private fun restoreMapPosition() {
        if (prefs.hasMapPosition) {
            map.controller.setZoom(prefs.mapZoom.coerceIn(2.0, 20.0))
            map.controller.setCenter(GeoPoint(prefs.mapLat, prefs.mapLon))
        } else {
            map.controller.setZoom(18.0)
            map.controller.setCenter(GeoPoint(48.2082, 16.3738))
        }
    }

    private fun saveMapPosition() {
        if (!::map.isInitialized) return
        runCatching {
            val center = map.mapCenter
            prefs.saveMapPosition(center.latitude, center.longitude, map.zoomLevelDouble)
        }
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setFollowing(enabled: Boolean, center: Boolean) {
        followMode = if (enabled) FollowMode.LOCATION else FollowMode.OFF
        updateFollowCamera(center)
    }

    /**
     * The location button cycles through three modes:
     * off -> follow position (north up) -> follow position + heading -> off.
     */
    private fun cycleFollowMode() {
        followMode = when (followMode) {
            FollowMode.OFF -> FollowMode.LOCATION
            FollowMode.LOCATION -> FollowMode.HEADING
            FollowMode.HEADING -> FollowMode.OFF
        }
        updateFollowCamera(center = true)
        toast(
            when (followMode) {
                FollowMode.OFF -> R.string.follow_off
                FollowMode.LOCATION -> R.string.follow_north
                FollowMode.HEADING -> R.string.follow_heading
            }
        )
    }

    private fun updateFollowCamera(center: Boolean) {
        if (followMode != FollowMode.HEADING) {
            rotationController.animateTo(0f)
        }
        if (followMode != FollowMode.OFF && center) {
            val location = lastLocation
            if (location != null && gpsFix) {
                val zoom = if (map.zoomLevelDouble < 13.0) 16.5 else map.zoomLevelDouble
                map.controller.animateTo(GeoPoint(location.latitude, location.longitude), zoom, 500L)
            }
        }
        applyHeadingOrientation()
        updateFollowUi()
    }

    private fun stopFollowingForGesture() {
        if (!following) return
        followMode = FollowMode.OFF
        // Keep the current orientation instead of snapping north in the middle
        // of the rotation gesture; the fingers now own the angle.
        rotationController.cancelAnimation()
        updateFollowUi()
    }

    private fun updateFollowUi() {
        val background = when (followMode) {
            FollowMode.OFF -> palette.fabBg
            FollowMode.LOCATION -> palette.accent
            FollowMode.HEADING -> palette.sat
        }
        val icon = if (followMode == FollowMode.OFF) palette.fabIcon else palette.accentInk
        btnFollow.backgroundTintList = ColorStateList.valueOf(background)
        btnFollow.imageTintList = ColorStateList.valueOf(icon)
        btnFollow.contentDescription = getString(
            when (followMode) {
                FollowMode.OFF -> R.string.follow_location
                FollowMode.LOCATION -> R.string.follow_north_description
                FollowMode.HEADING -> R.string.follow_heading_description
            }
        )
    }

    /** Snaps the map north without stopping location tracking. */
    private fun snapMapNorth() {
        if (followMode == FollowMode.HEADING) followMode = FollowMode.LOCATION
        rotationController.animateTo(0f)
        updateFollowUi()
    }

    private fun updateCameraModeUi() {
        btnCameraMode.text = getString(R.string.north_up)
        btnCameraMode.contentDescription = getString(R.string.snap_north)
    }

    private fun applyHeadingOrientation() {
        if (followMode != FollowMode.HEADING) return
        val heading = when {
            compassReady -> smoothAzimuth
            lastLocation?.hasBearing() == true -> lastLocation?.bearing ?: return
            else -> return
        }
        rotationController.animateTo(
            -normalizeDegrees(heading + magneticDeclination),
            tauSeconds = 0.045f,
            deadBandDegrees = 0.25f
        )
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
            batteryReceiverRegistered = true
        }
    }

    private fun unregisterBatteryReceiver() {
        if (!batteryReceiverRegistered) return
        runCatching { unregisterReceiver(batteryReceiver) }
        batteryReceiverRegistered = false
    }

    private fun lowPass(input: FloatArray, output: FloatArray, factor: Float) {
        for (index in 0 until minOf(input.size, output.size)) {
            output[index] += (input[index] - output[index]) * factor
        }
    }

    private fun blendAngle(from: Float, to: Float, factor: Float): Float {
        var difference = (to - from + 540f) % 360f - 180f
        if (difference < -180f) difference += 360f
        return normalizeDegrees(from + difference * factor)
    }

    private fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f

    private fun toast(resId: Int) {
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()
    }

    private val openSheetTokens = mutableSetOf<String>()

    private fun beginSheet(): String {
        val token = "sheet-${System.nanoTime()}"
        openSheetTokens.add(token)
        hud.pauseAutoCollapse()
        return token
    }

    private fun endSheet(token: String) {
        openSheetTokens.remove(token)
        if (openSheetTokens.isEmpty()) {
            hud.resumeAutoCollapse()
            window.decorView.postDelayed({ enableImmersiveMode() }, 220L)
        }
    }

    private fun openSettings() {
        if (settingsSheet?.isShowing == true) return
        val token = beginSheet()
        val sheet = SettingsSheet(
            this,
            prefs,
            object : SettingsSheet.Callbacks {
                override fun onAutoCollapseChanged(enabled: Boolean) {
                    hud.autoCollapseEnabled = enabled
                }

                override fun onKeepVisibleChanged(enabled: Boolean) {
                    hud.lockOpen = enabled
                }

                override fun onOrientationChanged(mode: OrientationMode) {
                    applyRequestedOrientation()
                }

                override fun onThemeSelected(mode: ThemeMode) {
                    applyTheme(mode)
                }

                override fun onMarkerStyleChanged(style: MarkerStyle) {
                    markerOverlay.setStyle(style)
                    map.invalidate()
                }

                override fun onExtraStatsChanged(enabled: Boolean) {
                    hud.setShowExtraStats(enabled)
                }

                override fun onUnitsChanged(units: UnitSystem) {
                    updateSpeedNow()
                    updateStats()
                }

                override fun onCoordFormatChanged(dms: Boolean) {
                    updateStats()
                }

                override fun onKeepScreenOnChanged(enabled: Boolean) {
                    applyKeepScreenOn()
                }

                override fun onOfflineChanged(enabled: Boolean) {
                    applyOfflineMode(enabled)
                }

                override fun onTracks() {
                    mainHandler.postDelayed({ openTracks() }, 420L)
                }

                override fun onClearCache(): Boolean {
                    clearTileCache()
                    return true
                }

                override fun onResetSettings() {
                    resetAllSettings()
                }

                override fun onClosed() {
                    settingsSheet = null
                    endSheet(token)
                }
            }
        )
        settingsSheet = sheet
        sheet.show()
    }

    private fun applyTheme(mode: ThemeMode) {
        if (mode == prefs.theme) return
        saveMapPosition()
        prefs.theme = mode
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) recreate()
        }, 450L)
    }

    private fun applyOfflineMode(enabled: Boolean) {
        prefs.offlineOnly = enabled
        MapStyles.apply(map, prefs.theme, enabled)
        toast(if (enabled) R.string.offline_mode_on else R.string.offline_mode_off)
    }

    private fun clearTileCache() {
        MapStyles.clearTileCache(map)
        map.invalidate()
        toast(R.string.cache_cleared)
    }

    private fun resetAllSettings() {
        prefs.reset()
        hud.autoCollapseEnabled = true
        hud.lockOpen = false
        hud.setShowExtraStats(true)
        applyRequestedOrientation()
        applyKeepScreenOn()
        toast(R.string.settings_reset)
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) recreate()
        }, 450L)
    }

    private fun openLayers() {
        if (layersSheet?.isShowing == true) return
        val token = beginSheet()
        val sheet = LayersSheet(
            this,
            prefs,
            object : LayersSheet.Callbacks {
                override fun onThemeSettings() {
                    mainHandler.postDelayed({ openSettings() }, 420L)
                }

                override fun onOfflineChanged(enabled: Boolean) {
                    applyOfflineMode(enabled)
                }

                override fun onClearCache() {
                    clearTileCache()
                }

                override fun onClosed() {
                    layersSheet = null
                    endSheet(token)
                }
            }
        )
        layersSheet = sheet
        sheet.show()
    }

    private fun openSearch() = openSearch(null, null)

    private fun openSearch(title: String?, routeTarget: RouteTarget?) {
        if (searchSheet?.isShowing == true) return
        val token = beginSheet()
        val sheet = SearchSheet(
            this,
            title,
            object : SearchSheet.Callbacks {
                override fun onPlacePicked(place: PlaceResult) {
                    if (routeTarget != null) {
                        mainHandler.postDelayed({
                            setFollowing(false, center = false)
                            val point = RoutePoint(
                                place.latitude,
                                place.longitude,
                                place.displayName
                            )
                            val bothSet = setRoutePoint(routeTarget, point)
                            if (!bothSet) {
                                map.controller.animateTo(
                                    GeoPoint(place.latitude, place.longitude),
                                    15.5,
                                    700L
                                )
                            }
                            toast(
                                if (routeTarget == RouteTarget.START) R.string.route_set_start
                                else R.string.route_set_destination
                            )
                        }, 420L)
                    } else {
                        mainHandler.postDelayed({ showPlace(place) }, 420L)
                    }
                }

                override fun onClosed() {
                    searchSheet = null
                    endSheet(token)
                }
            }
        )
        searchSheet = sheet
        sheet.show()
    }

    private fun showPlace(place: PlaceResult) {
        setFollowing(false, center = false)
        map.controller.animateTo(GeoPoint(place.latitude, place.longitude), 15.5, 700L)
        pinOverlay.show(place.latitude, place.longitude)
        currentPinLatitude = place.latitude
        currentPinLongitude = place.longitude
        currentPinLabel = place.displayName
        map.invalidate()
        mainHandler.postDelayed({ openPinSheet(place.displayName) }, 750L)
    }

    private fun openTracks() {
        if (tracksSheet?.isShowing == true) return
        val token = beginSheet()
        val sheet = TracksSheet(
            this,
            prefs,
            displayedTrack,
            object : TracksSheet.Callbacks {
                override fun onShowTrack(track: TrackSummary) {
                    mainHandler.postDelayed({ displayTrack(track) }, 380L)
                }

                override fun onHideTrack() {
                    clearTrackLine()
                    toast(R.string.track_hidden)
                }

                override fun onRouteTrack(track: TrackSummary) {
                    mainHandler.postDelayed({ useTrackAsRoute(track) }, 380L)
                }

                override fun onShareTrack(track: TrackSummary) {
                    shareTrack(track)
                }

                override fun onTrackRenamed(track: TrackSummary, newName: String) {
                    if (displayedTrack == track.file) {
                        trackPill.text = getString(R.string.track_pill, newName)
                        trackLine?.title = newName
                    }
                    if (prefs.routeTrackPath == track.file.absolutePath) {
                        prefs.routeTrackName = newName
                        updateRouteStatus()
                        routeSheet?.refresh()
                    }
                    toast(R.string.track_renamed)
                }

                override fun onTrackDeleted(track: TrackSummary) {
                    if (displayedTrack == track.file) clearTrackLine()
                    if (prefs.routeTrackPath == track.file.absolutePath) {
                        clearRoute(announce = false)
                    }
                    toast(R.string.track_deleted)
                }

                override fun onClosed() {
                    tracksSheet = null
                    endSheet(token)
                }
            }
        )
        tracksSheet = sheet
        sheet.show()
    }

    private fun shareTrack(track: TrackSummary) {
        runCatching {
            startActivity(
                Intent.createChooser(
                    TrackStore.shareIntent(this, track.file),
                    getString(R.string.gpx_share_title)
                )
            )
        }.onFailure {
            toast(R.string.track_not_found)
        }
    }

    private fun displayTrack(track: TrackSummary, announce: Boolean = true) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val points = runCatching { TrackStore.load(track.file) }.getOrDefault(emptyList())
                val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
                geoPoints to GeoSimplify.simplifyForDisplay(geoPoints)
            }
            val geoPoints = loaded.first
            if (geoPoints.size < 2) {
                toast(R.string.track_not_found)
                return@launch
            }
            clearTrackLine()
            val line = Polyline().apply {
                setPoints(loaded.second)
                outlinePaint.color = palette.accent
                outlinePaint.strokeWidth = 6f * resources.displayMetrics.density
                title = track.name
            }
            map.overlays.add(0, line)
            trackLine = line
            displayedTrack = track.file
            trackPill.text = getString(R.string.track_pill, track.name)
            trackPill.visibility = android.view.View.VISIBLE
            setFollowing(false, center = false)
            runCatching {
                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geoPoints), true)
            }
            map.invalidate()
            if (announce) toast(R.string.track_opened)
        }
    }

    /**
     * Makes a recorded track the active route. The track path is drawn in the
     * route colour and progress/ETA are measured along it.
     */
    private fun useTrackAsRoute(track: TrackSummary) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val points = runCatching { TrackStore.load(track.file) }.getOrDefault(emptyList())
                val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
                geoPoints to GeoSimplify.simplifyForDisplay(geoPoints)
            }
            val geoPoints = loaded.first
            if (geoPoints.size < 2) {
                toast(R.string.track_not_found)
                return@launch
            }
            val start = RoutePoint(
                geoPoints.first().latitude,
                geoPoints.first().longitude,
                getString(R.string.track_route_start)
            )
            val end = RoutePoint(
                geoPoints.last().latitude,
                geoPoints.last().longitude,
                getString(R.string.track_route_end)
            )
            prefs.routeStart = start
            prefs.routeDestination = end
            routeOverlay.setStart(start)
            routeOverlay.setDestination(end)
            prefs.routeTrackPath = track.file.absolutePath
            prefs.routeTrackName = track.name
            setRoutePath(geoPoints, loaded.second)
            prefs.routeTrackDistanceMeters = routePathTotalMeters
            setFollowing(false, center = false)
            runCatching {
                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geoPoints), true)
            }
            map.invalidate()
            updateRouteStatus()
            routeSheet?.refresh()
            toast(R.string.track_route_set)
        }
    }

    private fun clearTrackLine() {
        trackLine?.let { map.overlays.remove(it) }
        trackLine = null
        displayedTrack = null
        trackPill.visibility = android.view.View.GONE
        map.invalidate()
        tracksSheet?.refresh()
    }

    private fun dropPin(point: GeoPoint) {
        setFollowing(false, center = false)
        pinOverlay.show(point.latitude, point.longitude)
        currentPinLatitude = point.latitude
        currentPinLongitude = point.longitude
        currentPinLabel = null
        map.invalidate()
        pinSheet?.dismiss()
        mainHandler.postDelayed({ openPinSheet(null) }, 320L)
    }

    private fun openPinSheet(address: String?) {
        if (currentPinLatitude.isNaN() || currentPinLongitude.isNaN()) return
        if (pinSheet?.isShowing == true) return
        val token = beginSheet()
        val sheet = PinSheet(
            this,
            prefs,
            currentPinLatitude,
            currentPinLongitude,
            address,
            object : PinSheet.Callbacks {
                override fun onAddressResolved(label: String) {
                    currentPinLabel = label
                }

                override fun onSetStart(label: String) {
                    applyRoutePointFromPin(RouteTarget.START, label)
                }

                override fun onSetDestination(label: String) {
                    applyRoutePointFromPin(RouteTarget.DESTINATION, label)
                }

                override fun onRemovePin() {
                    pinOverlay.hide()
                    currentPinLatitude = Double.NaN
                    currentPinLongitude = Double.NaN
                    currentPinLabel = null
                    map.invalidate()
                }

                override fun onClosed() {
                    pinSheet = null
                    endSheet(token)
                }
            }
        )
        pinSheet = sheet
        sheet.show()
    }

    private fun applyRoutePointFromPin(target: RouteTarget, label: String) {
        if (currentPinLatitude.isNaN() || currentPinLongitude.isNaN()) return
        val point = RoutePoint(currentPinLatitude, currentPinLongitude, label)
        pinOverlay.hide()
        currentPinLatitude = Double.NaN
        currentPinLongitude = Double.NaN
        currentPinLabel = null
        map.invalidate()
        setRoutePoint(target, point)
        toast(
            if (target == RouteTarget.START) R.string.route_set_start
            else R.string.route_set_destination
        )
    }

    private fun openRouteSheet() {
        if (routeSheet?.isShowing == true) return
        val token = beginSheet()
        val sheet = RouteSheet(
            this,
            prefs,
            object : RouteSheet.Callbacks {
                override fun onPickCurrent(target: RouteTarget) {
                    val location = lastLocation
                    if (!gpsFix || location == null) {
                        toast(R.string.route_location_missing)
                        return
                    }
                    setRoutePoint(
                        target,
                        RoutePoint(
                            location.latitude,
                            location.longitude,
                            getString(R.string.route_my_location)
                        )
                    )
                    toast(
                        if (target == RouteTarget.START) R.string.route_set_start
                        else R.string.route_set_destination
                    )
                }

                override fun onPickPin(target: RouteTarget) {
                    if (currentPinLatitude.isNaN() || currentPinLongitude.isNaN()) {
                        toast(R.string.route_pin_missing)
                        return
                    }
                    val label = currentPinLabel?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.dropped_pin)
                    setRoutePoint(
                        target,
                        RoutePoint(currentPinLatitude, currentPinLongitude, label)
                    )
                    toast(
                        if (target == RouteTarget.START) R.string.route_set_start
                        else R.string.route_set_destination
                    )
                }

                override fun onSearch(target: RouteTarget) {
                    val title = getString(
                        if (target == RouteTarget.START) R.string.search_start_title
                        else R.string.search_destination_title
                    )
                    routeSheet?.dismiss()
                    mainHandler.postDelayed({
                        openSearch(title, target)
                    }, 420L)
                }

                override fun onClear(target: RouteTarget) {
                    clearRoutePoint(target)
                }

                override fun onClearAll() {
                    clearRoute()
                }

                override fun onClosed() {
                    routeSheet = null
                    endSheet(token)
                }
            }
        )
        routeSheet = sheet
        sheet.show()
    }

    /**
     * Stores one route endpoint, redraws the overlay and zooms out when both
     * endpoints exist. Returns true when the map shows the whole route.
     */
    private fun setRoutePoint(target: RouteTarget, point: RoutePoint): Boolean {
        if (prefs.hasTrackRoute) clearRoutePath()
        when (target) {
            RouteTarget.START -> {
                prefs.routeStart = point
                routeOverlay.setStart(point)
            }

            RouteTarget.DESTINATION -> {
                prefs.routeDestination = point
                routeOverlay.setDestination(point)
            }
        }
        map.invalidate()
        updateRouteStatus()
        routeSheet?.refresh()

        val start = prefs.routeStart
        val destination = prefs.routeDestination
        if (start == null || destination == null) return false
        setFollowing(false, center = false)
        runCatching {
            map.zoomToBoundingBox(
                BoundingBox.fromGeoPoints(
                    listOf(
                        GeoPoint(start.latitude, start.longitude),
                        GeoPoint(destination.latitude, destination.longitude)
                    )
                ),
                true
            )
        }
        return true
    }

    private fun clearRoutePoint(target: RouteTarget) {
        if (prefs.hasTrackRoute) clearRoutePath()
        when (target) {
            RouteTarget.START -> {
                prefs.routeStart = null
                routeOverlay.setStart(null)
            }

            RouteTarget.DESTINATION -> {
                prefs.routeDestination = null
                routeOverlay.setDestination(null)
            }
        }
        map.invalidate()
        updateRouteStatus()
        routeSheet?.refresh()
    }

    private fun clearRoute(announce: Boolean = true) {
        clearRoutePath()
        prefs.clearRoute()
        routeOverlay.clear()
        map.invalidate()
        updateRouteStatus()
        routeSheet?.refresh()
        if (announce) toast(R.string.route_cleared)
    }

    private fun toggleRecording() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (!hasLocationPermission()) {
            showPermissionRationale()
            return
        }
        recorder.start()
        recording = true
        hud.setRecording(true)
        recordingPill.visibility = android.view.View.VISIBLE
        recordingPill.text = getString(R.string.recording_pill, Fmt.duration(0L))
        toast(R.string.recording_started)
    }

    private fun stopRecording() {
        val points = recorder.stop()
        val startedAt = recorder.startedAtMillis
        recording = false
        hud.setRecording(false)
        recordingPill.visibility = android.view.View.GONE
        if (points.size < 2) {
            toast(R.string.track_too_short)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = TrackStore.save(this@MainActivity, points, startedAt)
                    var distance = 0.0
                    val result = FloatArray(1)
                    for (index in 1 until points.size) {
                        Location.distanceBetween(
                            points[index - 1].latitude,
                            points[index - 1].longitude,
                            points[index].latitude,
                            points[index].longitude,
                            result
                        )
                        distance += result[0]
                    }
                    TrackSummary(
                        file = file,
                        name = Fmt.dateTime(startedAt),
                        startedAt = startedAt,
                        durationMillis = (points.last().timeMillis - startedAt).coerceAtLeast(0L),
                        distanceMeters = distance,
                        pointCount = points.size
                    )
                }.getOrNull()
            }
            if (result == null) {
                toast(R.string.track_save_failed)
            } else {
                toast(R.string.track_saved)
                // Show the freshly recorded path right away so it is obvious
                // where the track went.
                mainHandler.postDelayed({ displayTrack(result, announce = false) }, 280L)
            }
        }
    }

    // FEATURES_PLACEHOLDER

    private companion object {
        /** Time constant for the fused rotation vector; quick but calm. */
        private const val SENSOR_TAU_SECONDS = 0.045f

        /** Raw accelerometer/magnetometer need more filtering. */
        private const val FALLBACK_TAU_SECONDS = 0.12f

        private const val COMPASS_HUD_INTERVAL_NANOS = 33_000_000L
    }
}
