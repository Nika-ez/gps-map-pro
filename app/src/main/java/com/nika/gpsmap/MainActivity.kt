package com.nika.gpsmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.nika.gpsmap.data.TrackPoint
import com.nika.gpsmap.data.TrackRecorder
import com.nika.gpsmap.data.TrackStore
import com.nika.gpsmap.data.TrackSummary
import com.nika.gpsmap.data.TripComputer
import com.nika.gpsmap.map.LocationMarkerOverlay
import com.nika.gpsmap.map.MapStyles
import com.nika.gpsmap.map.PinOverlay
import com.nika.gpsmap.net.PlaceResult
import com.nika.gpsmap.ui.HudPanelView
import com.nika.gpsmap.ui.LayersSheet
import com.nika.gpsmap.ui.PinSheet
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
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var palette: Palette
    private lateinit var map: MapView
    private lateinit var hud: HudPanelView
    private lateinit var btnFollow: FloatingActionButton
    private lateinit var btnCameraMode: MaterialButton
    private lateinit var recordingPill: TextView
    private lateinit var markerOverlay: LocationMarkerOverlay
    private lateinit var pinOverlay: PinOverlay
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trip = TripComputer()
    private val recorder = TrackRecorder()

    private var lastLocation: Location? = null
    private var gpsFix = false
    private var lastFixRealtime = 0L
    private var following = true
    private var batteryPercent = 0
    private var lastBatteryShown = -1
    private var lastTimeShown = ""
    private var flashPhase = false
    private var permissionDialogShown = false
    private var locationOffDialogShown = false
    private var recording = false
    private var currentPinLatitude = Double.NaN
    private var currentPinLongitude = Double.NaN
    private var displayedTrack: File? = null
    private var trackLine: Polyline? = null
    private var batteryReceiverRegistered = false
    private var gnssRegistered = false

    private var accelSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private val accelerometer = FloatArray(3)
    private val magnetometer = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var hasAccelerometer = false
    private var hasMagnetometer = false
    private var compassReady = false
    private var smoothAzimuth = 0f
    private var magneticDeclination = 0f

    private var settingsSheet: SettingsSheet? = null
    private var searchSheet: SearchSheet? = null
    private var layersSheet: LayersSheet? = null
    private var tracksSheet: TracksSheet? = null
    private var pinSheet: PinSheet? = null

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
                Sensor.TYPE_ACCELEROMETER -> {
                    lowPass(event.values, accelerometer, 0.18f)
                    hasAccelerometer = true
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    lowPass(event.values, magnetometer, 0.18f)
                    hasMagnetometer = true
                }
            }
            if (!hasAccelerometer || !hasMagnetometer) return
            if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelerometer, magnetometer)) return
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuth = normalizeDegrees(Math.toDegrees(orientationAngles[0].toDouble()).toFloat())
            smoothAzimuth = if (!compassReady) azimuth else blendAngle(smoothAzimuth, azimuth, 0.14f)
            compassReady = true
            hud.setCompassAzimuth(smoothAzimuth)
            if (following && prefs.cameraHeadingUp) {
                map.setMapOrientation(-normalizeDegrees(smoothAzimuth + magneticDeclination))
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val uiTicker = object : Runnable {
        override fun run() {
            updateClockAndBattery()
            val now = SystemClock.elapsedRealtime()
            if (gpsFix && lastFixRealtime > 0L && now - lastFixRealtime > 15_000L) {
                setGpsFix(false)
            }
            updateStats()
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
        MapStyles.configure(this)
        palette = Palette.from(this, prefs.theme)
        window.statusBarColor = palette.statusBar
        window.navigationBarColor = palette.statusBar

        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        hud = findViewById(R.id.hudPanel)
        btnFollow = findViewById(R.id.btnFollow)
        btnCameraMode = findViewById(R.id.btnCameraMode)
        recordingPill = findViewById(R.id.recordingPill)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setupMap()
        setupHud()
        setupControls()
        restoreMapPosition()
        updateCameraModeUi()
        updateFollowUi()
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
        applyKeepScreenOn()
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
        unregisterBatteryReceiver()
        saveMapPosition()
        super.onPause()
    }

    private fun setupMap() {
        map.setMultiTouchControls(true)
        MapStyles.apply(map, prefs.theme, prefs.offlineOnly)
        map.controller.setZoom(18.0)
        map.controller.setCenter(GeoPoint(48.2082, 16.3738))

        pinOverlay = PinOverlay().apply { setPalette(palette.accent) }
        markerOverlay = LocationMarkerOverlay().apply { setStyle(prefs.markerStyle) }
        map.overlays.add(pinOverlay)
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

        map.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                stopFollowingForGesture()
            }
            false
        }
    }

    private fun setupHud() {
        hud.applyPalette(palette)
        hud.autoCollapseEnabled = prefs.autoCollapse
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
        findViewById<FloatingActionButton>(R.id.btnSearch).setOnClickListener { openSearch() }
        findViewById<FloatingActionButton>(R.id.btnLayers).setOnClickListener { openLayers() }
        btnFollow.setOnClickListener {
            if (!following && !hasLocationPermission()) {
                requestLocationPermissionNow()
                return@setOnClickListener
            }
            setFollowing(!following, center = true)
        }
        btnCameraMode.setOnClickListener { toggleCameraMode() }
    }

    private fun setupSensors() {
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    private fun startSensors() {
        if (accelSensor == null && magSensor == null) {
            setupSensors()
        }
        accelSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        magSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
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
            providers.add(LocationManager.GPS_PROVIDER)
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
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
        val wasMissing = !gpsFix
        lastLocation = copy
        lastFixRealtime = SystemClock.elapsedRealtime()
        trip.onLocation(copy, lastFixRealtime)
        recorder.add(copy)
        setGpsFix(true)

        if (copy.hasAltitude()) {
            runCatching {
                magneticDeclination = GeomagneticField(
                    copy.latitude.toFloat(),
                    copy.longitude.toFloat(),
                    copy.altitude.toFloat(),
                    System.currentTimeMillis()
                ).declination
            }
        }

        val heading = when {
            compassReady -> smoothAzimuth
            copy.hasBearing() -> copy.bearing
            else -> null
        }
        markerOverlay.update(copy.latitude, copy.longitude, copy.accuracy, heading, true)

        if (following) {
            if (wasMissing) {
                val zoom = if (map.zoomLevelDouble < 13.0) 16.5 else map.zoomLevelDouble
                map.controller.animateTo(GeoPoint(copy.latitude, copy.longitude), zoom, 600L)
            } else {
                map.controller.setCenter(GeoPoint(copy.latitude, copy.longitude))
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
        following = enabled
        updateFollowUi()
        if (!enabled) {
            map.setMapOrientation(0f)
            return
        }
        if (!prefs.cameraHeadingUp) map.setMapOrientation(0f)
        val location = lastLocation
        if (center && location != null) {
            val zoom = if (map.zoomLevelDouble < 13.0) 16.5 else map.zoomLevelDouble
            map.controller.animateTo(GeoPoint(location.latitude, location.longitude), zoom, 500L)
        }
        applyHeadingOrientation()
    }

    private fun stopFollowingForGesture() {
        if (!following) return
        following = false
        map.setMapOrientation(0f)
        updateFollowUi()
    }

    private fun updateFollowUi() {
        val background = if (following) palette.accent else palette.fabBg
        val icon = if (following) palette.accentInk else palette.fabIcon
        btnFollow.backgroundTintList = ColorStateList.valueOf(background)
        btnFollow.imageTintList = ColorStateList.valueOf(icon)
        btnFollow.contentDescription = getString(R.string.follow_location)
    }

    private fun toggleCameraMode() {
        prefs.cameraHeadingUp = !prefs.cameraHeadingUp
        updateCameraModeUi()
        if (prefs.cameraHeadingUp) {
            applyHeadingOrientation()
        } else {
            map.setMapOrientation(0f)
        }
    }

    private fun updateCameraModeUi() {
        btnCameraMode.text = getString(
            if (prefs.cameraHeadingUp) R.string.heading_up else R.string.north_up
        )
        btnCameraMode.contentDescription = getString(R.string.camera_mode)
    }

    private fun applyHeadingOrientation() {
        if (!following || !prefs.cameraHeadingUp) return
        val heading = when {
            compassReady -> smoothAzimuth
            lastLocation?.hasBearing() == true -> lastLocation?.bearing ?: 0f
            else -> return
        }
        map.setMapOrientation(-normalizeDegrees(heading + magneticDeclination))
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
        if (openSheetTokens.isEmpty()) hud.resumeAutoCollapse()
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

                override fun onThemeSelected(mode: ThemeMode) {
                    applyTheme(mode)
                }

                override fun onMarkerStyleChanged(style: MarkerStyle) {
                    markerOverlay.setStyle(style)
                    map.invalidate()
                }

                override fun onCameraHeadingChanged(enabled: Boolean) {
                    updateCameraModeUi()
                    if (enabled) applyHeadingOrientation() else map.setMapOrientation(0f)
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
        hud.setShowExtraStats(true)
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

    private fun openSearch() {
        if (searchSheet?.isShowing == true) return
        val token = beginSheet()
        val sheet = SearchSheet(
            this,
            object : SearchSheet.Callbacks {
                override fun onPlacePicked(place: PlaceResult) {
                    mainHandler.postDelayed({ showPlace(place) }, 420L)
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
        map.invalidate()
        mainHandler.postDelayed({ openPinSheet(place.displayName) }, 750L)
    }

    private fun openTracks() {
        if (tracksSheet?.isShowing == true) return
        val token = beginSheet()
        val sheet = TracksSheet(
            this,
            prefs,
            object : TracksSheet.Callbacks {
                override fun onShowTrack(track: TrackSummary) {
                    mainHandler.postDelayed({ displayTrack(track) }, 380L)
                }

                override fun onShareTrack(track: TrackSummary) {
                    shareTrack(track)
                }

                override fun onTrackDeleted(track: TrackSummary) {
                    if (displayedTrack == track.file) clearTrackLine()
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

    private fun displayTrack(track: TrackSummary) {
        lifecycleScope.launch {
            val points = withContext(Dispatchers.IO) {
                runCatching { TrackStore.load(track.file) }.getOrDefault(emptyList())
            }
            if (points.size < 2) {
                toast(R.string.track_not_found)
                return@launch
            }
            clearTrackLine()
            val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
            val line = Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = palette.accent
                outlinePaint.strokeWidth = 6f * resources.displayMetrics.density
                title = track.name
            }
            map.overlays.add(0, line)
            trackLine = line
            displayedTrack = track.file
            setFollowing(false, center = false)
            runCatching {
                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geoPoints), true)
            }
            map.invalidate()
            toast(R.string.track_opened)
        }
    }

    private fun clearTrackLine() {
        trackLine?.let { map.overlays.remove(it) }
        trackLine = null
        displayedTrack = null
        map.invalidate()
    }

    private fun dropPin(point: GeoPoint) {
        setFollowing(false, center = false)
        pinOverlay.show(point.latitude, point.longitude)
        currentPinLatitude = point.latitude
        currentPinLongitude = point.longitude
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
                override fun onRemovePin() {
                    pinOverlay.hide()
                    currentPinLatitude = Double.NaN
                    currentPinLongitude = Double.NaN
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
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    TrackStore.save(this@MainActivity, points, startedAt)
                }.getOrNull()
            }
            toast(if (file != null) R.string.track_saved else R.string.track_save_failed)
        }
    }

    // FEATURES_PLACEHOLDER
}
