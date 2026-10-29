package com.nika.gpsmap.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.nika.gpsmap.BuildConfig
import com.nika.gpsmap.Fmt
import com.nika.gpsmap.MarkerStyle
import com.nika.gpsmap.Prefs
import com.nika.gpsmap.R
import com.nika.gpsmap.ThemeMode
import com.nika.gpsmap.UnitSystem
import com.nika.gpsmap.map.MapStyles

class SettingsSheet(
    private val context: Context,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onAutoCollapseChanged(enabled: Boolean)
        fun onThemeSelected(mode: ThemeMode)
        fun onMarkerStyleChanged(style: MarkerStyle)
        fun onCameraHeadingChanged(enabled: Boolean)
        fun onExtraStatsChanged(enabled: Boolean)
        fun onUnitsChanged(units: UnitSystem)
        fun onCoordFormatChanged(dms: Boolean)
        fun onKeepScreenOnChanged(enabled: Boolean)
        fun onOfflineChanged(enabled: Boolean)
        fun onTracks()
        fun onClearCache(): Boolean
        fun onResetSettings()
        fun onClosed()
    }

    private var dialog: BottomSheetDialog? = null

    val isShowing: Boolean
        get() = dialog?.isShowing == true

    fun show() {
        if (dialog?.isShowing == true) return
        val sheet = BottomSheetDialog(context)
        dialog = sheet
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_settings, null)
        sheet.setContentView(root)
        sheet.setOnDismissListener { callbacks.onClosed() }

        root.findViewById<ImageButton>(R.id.btnCloseSettings).setOnClickListener { sheet.dismiss() }

        val switchAuto = root.findViewById<MaterialSwitch>(R.id.switchAutoCollapse)
        switchAuto.isChecked = prefs.autoCollapse
        switchAuto.setOnCheckedChangeListener { _, checked ->
            prefs.autoCollapse = checked
            callbacks.onAutoCollapseChanged(checked)
        }

        val spinnerTheme = root.findViewById<Spinner>(R.id.spinnerTheme)
        val themeModes = ThemeMode.entries
        val themeAdapter = ArrayAdapter(
            context,
            R.layout.item_spinner,
            themeModes.map { context.getString(it.labelRes) }
        )
        themeAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinnerTheme.adapter = themeAdapter
        spinnerTheme.setSelection(prefs.theme.ordinal, false)
        var themeInitial = true
        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (themeInitial) {
                    themeInitial = false
                    return
                }
                val mode = themeModes.getOrNull(position) ?: return
                if (mode == prefs.theme) return
                callbacks.onThemeSelected(mode)
                sheet.dismiss()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val spinnerMarker = root.findViewById<Spinner>(R.id.spinnerMarker)
        val markerStyles = MarkerStyle.entries
        val markerAdapter = ArrayAdapter(
            context,
            R.layout.item_spinner,
            markerStyles.map { context.getString(it.labelRes) }
        )
        markerAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinnerMarker.adapter = markerAdapter
        spinnerMarker.setSelection(prefs.markerStyle.ordinal, false)
        var markerInitial = true
        spinnerMarker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (markerInitial) {
                    markerInitial = false
                    return
                }
                val style = markerStyles.getOrNull(position) ?: return
                if (style == prefs.markerStyle) return
                prefs.markerStyle = style
                callbacks.onMarkerStyleChanged(style)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val switchHeading = root.findViewById<MaterialSwitch>(R.id.switchCameraHeading)
        switchHeading.isChecked = prefs.cameraHeadingUp
        switchHeading.setOnCheckedChangeListener { _, checked ->
            prefs.cameraHeadingUp = checked
            callbacks.onCameraHeadingChanged(checked)
        }

        val switchExtra = root.findViewById<MaterialSwitch>(R.id.switchExtraStats)
        switchExtra.isChecked = prefs.showExtraStats
        switchExtra.setOnCheckedChangeListener { _, checked ->
            prefs.showExtraStats = checked
            callbacks.onExtraStatsChanged(checked)
        }

        val spinnerUnits = root.findViewById<Spinner>(R.id.spinnerUnits)
        val unitOptions = listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL)
        val unitAdapter = ArrayAdapter(
            context,
            R.layout.item_spinner,
            listOf(context.getString(R.string.units_metric), context.getString(R.string.units_imperial))
        )
        unitAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinnerUnits.adapter = unitAdapter
        spinnerUnits.setSelection(unitOptions.indexOf(prefs.units), false)
        var unitsInitial = true
        spinnerUnits.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (unitsInitial) {
                    unitsInitial = false
                    return
                }
                val units = unitOptions.getOrNull(position) ?: return
                if (units == prefs.units) return
                prefs.units = units
                callbacks.onUnitsChanged(units)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val spinnerCoords = root.findViewById<Spinner>(R.id.spinnerCoords)
        val coordAdapter = ArrayAdapter(
            context,
            R.layout.item_spinner,
            listOf(context.getString(R.string.coord_decimal), context.getString(R.string.coord_dms))
        )
        coordAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinnerCoords.adapter = coordAdapter
        spinnerCoords.setSelection(if (prefs.coordDms) 1 else 0, false)
        var coordsInitial = true
        spinnerCoords.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (coordsInitial) {
                    coordsInitial = false
                    return
                }
                val dms = position == 1
                if (dms == prefs.coordDms) return
                prefs.coordDms = dms
                callbacks.onCoordFormatChanged(dms)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val switchKeepScreen = root.findViewById<MaterialSwitch>(R.id.switchKeepScreenOn)
        switchKeepScreen.isChecked = prefs.keepScreenOn
        switchKeepScreen.setOnCheckedChangeListener { _, checked ->
            prefs.keepScreenOn = checked
            callbacks.onKeepScreenOnChanged(checked)
        }

        val switchOffline = root.findViewById<MaterialSwitch>(R.id.switchOffline)
        switchOffline.isChecked = prefs.offlineOnly
        switchOffline.setOnCheckedChangeListener { _, checked ->
            prefs.offlineOnly = checked
            callbacks.onOfflineChanged(checked)
        }

        val cacheText = root.findViewById<TextView>(R.id.tvCacheSize)
        val clearCache = root.findViewById<View>(R.id.btnClearCache)
        fun refreshCache() {
            cacheText.text = context.getString(R.string.cache_size, Fmt.cacheSize(MapStyles.cacheSize(context)))
        }
        refreshCache()
        clearCache.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.clear_cache_confirm_title)
                .setMessage(R.string.clear_cache_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_cache) { _, _ ->
                    callbacks.onClearCache()
                    refreshCache()
                }
                .show()
        }

        root.findViewById<View>(R.id.btnSavedTracks).setOnClickListener {
            sheet.dismiss()
            callbacks.onTracks()
        }

        root.findViewById<View>(R.id.btnAbout).setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.about)
                .setMessage(
                    context.getString(R.string.about_body) + "\n\n" +
                        context.getString(R.string.about_credits) + "\n\n" +
                        context.getString(R.string.app_version, BuildConfig.VERSION_NAME)
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        root.findViewById<View>(R.id.btnResetSettings).setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.reset_settings_title)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_settings) { _, _ ->
                    callbacks.onResetSettings()
                    sheet.dismiss()
                }
                .show()
        }

        root.findViewById<TextView>(R.id.tvVersion).text =
            context.getString(R.string.app_version, BuildConfig.VERSION_NAME)

        sheet.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
