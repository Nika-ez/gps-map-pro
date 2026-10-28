package com.nika.gpsmap.map

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.views.MapView
import java.io.File

object MapStyles {

    private val cartoDark: XYTileSource by lazy {
        XYTileSource(
            "CartoDark",
            0,
            20,
            256,
            ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/",
                "https://d.basemaps.cartocdn.com/dark_all/"
            )
        )
    }

    private val cartoLight: XYTileSource by lazy {
        XYTileSource(
            "CartoLight",
            0,
            20,
            256,
            ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/light_all/",
                "https://b.basemaps.cartocdn.com/light_all/",
                "https://c.basemaps.cartocdn.com/light_all/",
                "https://d.basemaps.cartocdn.com/light_all/"
            )
        )
    }

    fun configure(context: Context) {
        val config = Configuration.getInstance()
        @Suppress("DEPRECATION")
        config.load(context, android.preference.PreferenceManager.getDefaultSharedPreferences(context))
        config.userAgentValue = "${context.packageName}/2.0 (Android)"
        config.tileFileSystemCacheMaxBytes = 80L * 1024L * 1024L
        config.tileFileSystemCacheTrimBytes = 64L * 1024L * 1024L
    }

    fun apply(map: MapView, mode: com.nika.gpsmap.ThemeMode, offlineOnly: Boolean) {
        val source = when (mode) {
            com.nika.gpsmap.ThemeMode.ORIGINAL -> TileSourceFactory.MAPNIK
            com.nika.gpsmap.ThemeMode.NIGHT -> cartoDark
            com.nika.gpsmap.ThemeMode.LIGHT -> cartoLight
        }
        map.setTileSource(source)
        map.setUseDataConnection(!offlineOnly)
    }

    fun cacheDirectory(context: Context): File {
        val configured = Configuration.getInstance().osmdroidTileCache
        return configured ?: File(context.cacheDir, "osmdroid")
    }

    fun cacheSize(context: Context): Long {
        val dir = cacheDirectory(context)
        if (!dir.exists()) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun clearTileCache(map: MapView): Boolean {
        return runCatching {
            map.tileProvider.clearTileCache()
            val writer = map.tileProvider.tileWriter
            if (writer is SqlTileWriter) {
                writer.purgeCache()
            } else {
                cacheDirectory(map.context).listFiles()?.forEach { child ->
                    child.deleteRecursively()
                }
            }
            map.invalidate()
            true
        }.getOrDefault(false)
    }
}
