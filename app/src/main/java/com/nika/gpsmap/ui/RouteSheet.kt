package com.nika.gpsmap.ui

import android.content.Context
import android.location.Location
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nika.gpsmap.Fmt
import com.nika.gpsmap.Prefs
import com.nika.gpsmap.R
import com.nika.gpsmap.RoutePoint

enum class RouteTarget {
    START,
    DESTINATION
}

class RouteSheet(
    private val context: Context,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onPickCurrent(target: RouteTarget)
        fun onPickPin(target: RouteTarget)
        fun onSearch(target: RouteTarget)
        fun onClear(target: RouteTarget)
        fun onClearAll()
        fun onClosed()
    }

    private var dialog: BottomSheetDialog? = null
    private var content: View? = null

    val isShowing: Boolean
        get() = dialog?.isShowing == true

    fun show() {
        if (dialog?.isShowing == true) return
        val sheet = BottomSheetDialog(context)
        dialog = sheet
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_route, null)
        content = root
        sheet.setContentView(root)
        sheet.setOnDismissListener {
            content = null
            callbacks.onClosed()
        }

        root.findViewById<ImageButton>(R.id.btnCloseRoute).setOnClickListener { sheet.dismiss() }

        root.findViewById<View>(R.id.rowStartChoose).setOnClickListener {
            showChoice(RouteTarget.START)
        }
        root.findViewById<View>(R.id.rowDestinationChoose).setOnClickListener {
            showChoice(RouteTarget.DESTINATION)
        }

        root.findViewById<MaterialButton>(R.id.btnStartLocation).setOnClickListener {
            callbacks.onPickCurrent(RouteTarget.START)
            refresh()
        }
        root.findViewById<MaterialButton>(R.id.btnDestinationLocation).setOnClickListener {
            callbacks.onPickCurrent(RouteTarget.DESTINATION)
            refresh()
        }
        root.findViewById<MaterialButton>(R.id.btnStartPin).setOnClickListener {
            callbacks.onPickPin(RouteTarget.START)
            refresh()
        }
        root.findViewById<MaterialButton>(R.id.btnDestinationPin).setOnClickListener {
            callbacks.onPickPin(RouteTarget.DESTINATION)
            refresh()
        }
        root.findViewById<MaterialButton>(R.id.btnStartSearch).setOnClickListener {
            callbacks.onSearch(RouteTarget.START)
        }
        root.findViewById<MaterialButton>(R.id.btnDestinationSearch).setOnClickListener {
            callbacks.onSearch(RouteTarget.DESTINATION)
        }
        root.findViewById<MaterialButton>(R.id.btnClearRoute).setOnClickListener {
            confirmClearAll()
        }

        refresh()
        sheet.showExpanded()
    }

    fun refresh() {
        val root = content ?: return
        val start = prefs.routeStart
        val destination = prefs.routeDestination

        bindPoint(
            root.findViewById(R.id.tvStartValue),
            root.findViewById(R.id.tvStartCoords),
            start
        )
        bindPoint(
            root.findViewById(R.id.tvDestinationValue),
            root.findViewById(R.id.tvDestinationCoords),
            destination
        )

        val distanceView = root.findViewById<TextView>(R.id.tvRouteDistance)
        val liveView = root.findViewById<TextView>(R.id.tvRouteLive)
        val hintView = root.findViewById<TextView>(R.id.tvRouteHint)
        val clear = root.findViewById<MaterialButton>(R.id.btnClearRoute)
        if (prefs.hasTrackRoute) {
            val name = prefs.routeTrackName ?: context.getString(R.string.route)
            distanceView.text = context.getString(
                R.string.route_track_distance,
                Fmt.distance(prefs.routeTrackDistanceMeters, prefs.units),
                name
            )
            hintView.text = context.getString(R.string.route_track_hint)
            hintView.visibility = View.VISIBLE
            clear.isEnabled = true
            clear.alpha = 1f
        } else if (start != null && destination != null) {
            val result = FloatArray(1)
            Location.distanceBetween(
                start.latitude,
                start.longitude,
                destination.latitude,
                destination.longitude,
                result
            )
            distanceView.text = context.getString(
                R.string.route_distance,
                Fmt.distance(result[0].toDouble(), prefs.units)
            )
            hintView.text = context.getString(R.string.route_distance_hint)
            hintView.visibility = View.VISIBLE
            clear.isEnabled = true
            clear.alpha = 1f
        } else {
            distanceView.text = context.getString(R.string.route_distance_hint)
            hintView.visibility = View.GONE
            clear.isEnabled = prefs.hasRoute
            clear.alpha = if (prefs.hasRoute) 1f else 0.45f
        }
        if (liveView.text.isNullOrBlank()) liveView.visibility = View.GONE
    }

    fun updateLiveStatus(text: String?) {
        val root = content ?: return
        val liveView = root.findViewById<TextView>(R.id.tvRouteLive) ?: return
        if (text.isNullOrBlank()) {
            liveView.text = ""
            liveView.visibility = View.GONE
        } else {
            liveView.text = text
            liveView.visibility = View.VISIBLE
        }
    }

    private fun bindPoint(value: TextView, coords: TextView, point: RoutePoint?) {
        if (point == null) {
            value.text = context.getString(R.string.route_not_set)
            coords.visibility = View.GONE
        } else {
            value.text = point.label.ifBlank { context.getString(R.string.dropped_pin) }
            coords.text = Fmt.coordinates(point.latitude, point.longitude, prefs.coordDms)
            coords.visibility = View.VISIBLE
        }
    }

    private fun showChoice(target: RouteTarget) {
        val point = pointFor(target)
        val labels = ArrayList<String>(4)
        labels.add(context.getString(R.string.route_my_location))
        labels.add(context.getString(R.string.route_use_pin))
        labels.add(context.getString(R.string.route_search))
        if (point != null) {
            labels.add(
                context.getString(
                    if (target == RouteTarget.START) R.string.route_clear_start
                    else R.string.route_clear_destination
                )
            )
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(
                context.getString(
                    if (target == RouteTarget.START) R.string.route_choose_start
                    else R.string.route_choose_destination
                )
            )
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        callbacks.onPickCurrent(target)
                        refresh()
                    }

                    1 -> {
                        callbacks.onPickPin(target)
                        refresh()
                    }

                    2 -> callbacks.onSearch(target)
                    3 -> {
                        callbacks.onClear(target)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.route_clear_confirm_title)
            .setMessage(R.string.route_clear_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.route_clear_all) { _, _ ->
                callbacks.onClearAll()
                refresh()
            }
            .show()
    }

    private fun pointFor(target: RouteTarget): RoutePoint? =
        if (target == RouteTarget.START) prefs.routeStart else prefs.routeDestination

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
