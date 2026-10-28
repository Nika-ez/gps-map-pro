package com.nika.gpsmap.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nika.gpsmap.Fmt
import com.nika.gpsmap.Prefs
import com.nika.gpsmap.R
import com.nika.gpsmap.data.TrackStore
import com.nika.gpsmap.data.TrackSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TracksSheet(
    private val context: Context,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onShowTrack(track: TrackSummary)
        fun onShareTrack(track: TrackSummary)
        fun onTrackDeleted(track: TrackSummary)
        fun onClosed()
    }

    private var dialog: BottomSheetDialog? = null

    val isShowing: Boolean
        get() = dialog?.isShowing == true
    private var scope: CoroutineScope? = null
    private var loadJob: Job? = null
    private var adapter: TrackAdapter? = null
    private var emptyView: TextView? = null

    fun show() {
        if (dialog?.isShowing == true) return
        val sheet = BottomSheetDialog(context)
        dialog = sheet
        val currentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = currentScope
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_tracks, null)
        sheet.setContentView(root)
        sheet.setOnDismissListener {
            loadJob?.cancel()
            currentScope.cancel()
            scope = null
            callbacks.onClosed()
        }

        root.findViewById<ImageButton>(R.id.btnCloseTracks).setOnClickListener { sheet.dismiss() }

        val list = root.findViewById<RecyclerView>(R.id.rvTracks)
        val empty = root.findViewById<TextView>(R.id.tvTracksEmpty)
        emptyView = empty
        list.layoutManager = LinearLayoutManager(context)
        val trackAdapter = TrackAdapter(
            units = prefs.units,
            onShow = { track ->
                sheet.dismiss()
                callbacks.onShowTrack(track)
            },
            onShare = { track -> callbacks.onShareTrack(track) },
            onDelete = { track -> confirmDelete(track) }
        )
        adapter = trackAdapter
        list.adapter = trackAdapter

        refresh(currentScope)
        sheet.show()
    }

    fun refresh() {
        val currentScope = scope ?: return
        refresh(currentScope)
    }

    private fun refresh(currentScope: CoroutineScope) {
        loadJob?.cancel()
        loadJob = currentScope.launch {
            val tracks = withContext(Dispatchers.IO) { TrackStore.list(context) }
            adapter?.submit(tracks)
            emptyView?.isVisible = tracks.isEmpty()
            dialog?.findViewById<RecyclerView>(R.id.rvTracks)?.isVisible = tracks.isNotEmpty()
        }
    }

    private fun confirmDelete(track: TrackSummary) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_track_title)
            .setMessage(context.getString(R.string.delete_track_message, track.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_track) { _, _ ->
                val currentScope = scope ?: return@setPositiveButton
                currentScope.launch {
                    val deleted = withContext(Dispatchers.IO) { TrackStore.delete(track.file) }
                    if (deleted) {
                        callbacks.onTrackDeleted(track)
                        refresh(currentScope)
                    }
                }
            }
            .show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private class TrackAdapter(
        private val units: com.nika.gpsmap.UnitSystem,
        private val onShow: (TrackSummary) -> Unit,
        private val onShare: (TrackSummary) -> Unit,
        private val onDelete: (TrackSummary) -> Unit
    ) : RecyclerView.Adapter<TrackAdapter.Holder>() {

        private val items = ArrayList<TrackSummary>()

        fun submit(newItems: List<TrackSummary>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_track, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val track = items[position]
            holder.name.text = track.name
            holder.meta.text = holder.itemView.context.getString(
                R.string.track_meta,
                Fmt.duration(track.durationMillis),
                Fmt.distance(track.distanceMeters, units),
                track.pointCount
            )
            holder.show.setOnClickListener { onShow(track) }
            holder.share.setOnClickListener { onShare(track) }
            holder.delete.setOnClickListener { onDelete(track) }
        }

        override fun getItemCount(): Int = items.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvTrackName)
            val meta: TextView = view.findViewById(R.id.tvTrackMeta)
            val show: MaterialButton = view.findViewById(R.id.btnShowTrack)
            val share: MaterialButton = view.findViewById(R.id.btnShareTrack)
            val delete: MaterialButton = view.findViewById(R.id.btnDeleteTrack)
        }
    }
}
