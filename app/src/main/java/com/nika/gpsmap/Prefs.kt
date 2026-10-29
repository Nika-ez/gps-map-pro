package com.nika.gpsmap

import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.StyleRes

enum class ThemeMode(
    val key: String,
    @StyleRes val styleRes: Int,
    @StringRes val labelRes: Int
) {
    ORIGINAL("original", R.style.Theme_GpsMap, R.string.theme_original),
    NIGHT("night", R.style.Theme_GpsMap_Night, R.string.theme_night),
    LIGHT("light", R.style.Theme_GpsMap_Light, R.string.theme_light);

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: ORIGINAL
    }
}

enum class UnitSystem {
    METRIC,
    IMPERIAL;

    companion object {
        fun fromKey(key: String?): UnitSystem =
            if (key == "imperial") IMPERIAL else METRIC
    }
}

enum class MarkerStyle(
    val key: String,
    @StringRes val labelRes: Int
) {
    DOT("dot", R.string.marker_dot),
    ARROW("arrow", R.string.marker_arrow),
    CAR("car", R.string.marker_car),
    PIN("pin", R.string.marker_pin);

    companion object {
        fun fromKey(key: String?): MarkerStyle =
            entries.firstOrNull { it.key == key } ?: DOT
    }
}

class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("gps_map_pro", Context.MODE_PRIVATE)

    var autoCollapse: Boolean
        get() = sp.getBoolean("auto_collapse", true)
        set(value) = sp.edit().putBoolean("auto_collapse", value).apply()

    var theme: ThemeMode
        get() = ThemeMode.fromKey(sp.getString("theme", ThemeMode.ORIGINAL.key))
        set(value) = sp.edit().putString("theme", value.key).apply()

    var units: UnitSystem
        get() = UnitSystem.fromKey(sp.getString("units", UnitSystem.METRIC.name.lowercase()))
        set(value) = sp.edit().putString("units", value.name.lowercase()).apply()

    var cameraHeadingUp: Boolean
        get() = sp.getBoolean("camera_heading_up", false)
        set(value) = sp.edit().putBoolean("camera_heading_up", value).apply()

    var showExtraStats: Boolean
        get() = sp.getBoolean("show_extra_stats", true)
        set(value) = sp.edit().putBoolean("show_extra_stats", value).apply()

    var markerStyle: MarkerStyle
        get() = MarkerStyle.fromKey(sp.getString("marker_style", MarkerStyle.DOT.key))
        set(value) = sp.edit().putString("marker_style", value.key).apply()

    var coordDms: Boolean
        get() = sp.getBoolean("coord_dms", false)
        set(value) = sp.edit().putBoolean("coord_dms", value).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(value) = sp.edit().putBoolean("keep_screen_on", value).apply()

    var offlineOnly: Boolean
        get() = sp.getBoolean("offline_only", false)
        set(value) = sp.edit().putBoolean("offline_only", value).apply()

    var mapLat: Double
        get() = sp.getFloat("map_lat", 0f).toDouble()
        set(value) = sp.edit().putFloat("map_lat", value.toFloat()).apply()

    var mapLon: Double
        get() = sp.getFloat("map_lon", 0f).toDouble()
        set(value) = sp.edit().putFloat("map_lon", value.toFloat()).apply()

    var mapZoom: Double
        get() = sp.getFloat("map_zoom", 0f).toDouble()
        set(value) = sp.edit().putFloat("map_zoom", value.toFloat()).apply()

    val hasMapPosition: Boolean
        get() = sp.getFloat("map_lat", 0f) != 0f && sp.getFloat("map_lon", 0f) != 0f

    fun saveMapPosition(lat: Double, lon: Double, zoom: Double) {
        sp.edit()
            .putFloat("map_lat", lat.toFloat())
            .putFloat("map_lon", lon.toFloat())
            .putFloat("map_zoom", zoom.toFloat())
            .apply()
    }

    fun reset() {
        sp.edit().clear().apply()
    }
}
