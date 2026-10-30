package com.nika.gpsmap.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.nika.gpsmap.Fmt
import com.nika.gpsmap.Prefs
import com.nika.gpsmap.R
import com.nika.gpsmap.map.MapStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LayersSheet(
    private val context: Context,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onThemeSettings()
        fun onOfflineChanged(enabled: Boolean)
        fun onClearCache()
        fun onClosed()
    }

    private var dialog: BottomSheetDialog? = null

    val isShowing: Boolean
        get() = dialog?.isShowing == true
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    fun show() {
        if (dialog?.isShowing == true) return
        val sheet = BottomSheetDialog(context)
        dialog = sheet
        val currentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = currentScope
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_layers, null)
        sheet.setContentView(root)
        sheet.setOnDismissListener {
            job?.cancel()
            currentScope.cancel()
            scope = null
            callbacks.onClosed()
        }

        root.findViewById<ImageButton>(R.id.btnCloseLayers).setOnClickListener { sheet.dismiss() }

        val themeValue = root.findViewById<TextView>(R.id.tvMapThemeValue)
        themeValue.text = context.getString(
            R.string.theme_value,
            context.getString(prefs.theme.labelRes)
        )
        root.findViewById<View>(R.id.tvMapTheme).setOnClickListener {
            sheet.dismiss()
            callbacks.onThemeSettings()
        }
        themeValue.setOnClickListener {
            sheet.dismiss()
            callbacks.onThemeSettings()
        }

        val offline = root.findViewById<MaterialSwitch>(R.id.switchOfflineLayers)
        offline.isChecked = prefs.offlineOnly
        offline.setOnCheckedChangeListener { _, checked ->
            callbacks.onOfflineChanged(checked)
        }

        val cacheText = root.findViewById<TextView>(R.id.tvCacheSizeLayers)
        fun refreshCache() {
            job?.cancel()
            job = currentScope.launch {
                val size = withContext(Dispatchers.IO) { MapStyles.cacheSize(context) }
                cacheText.text = context.getString(R.string.cache_size, Fmt.cacheSize(size))
            }
        }
        refreshCache()

        root.findViewById<MaterialButton>(R.id.btnClearCacheLayers).setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.clear_cache_confirm_title)
                .setMessage(R.string.clear_cache_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_cache) { _, _ ->
                    callbacks.onClearCache()
                    cacheText.postDelayed({ refreshCache() }, 350L)
                }
                .show()
        }

        root.findViewById<MaterialButton>(R.id.btnOsmInfo).setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.osm_copyright_title)
                .setMessage(R.string.osm_copyright_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        sheet.showExpanded()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
