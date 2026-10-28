package com.nika.gpsmap.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.nika.gpsmap.Fmt
import com.nika.gpsmap.R
import com.nika.gpsmap.net.NominatimClient
import com.nika.gpsmap.net.PlaceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SearchSheet(
    private val context: Context,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onPlacePicked(place: PlaceResult)
        fun onClosed()
    }

    private var dialog: BottomSheetDialog? = null

    val isShowing: Boolean
        get() = dialog?.isShowing == true
    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private val adapter = PlaceAdapter { place ->
        dialog?.dismiss()
        callbacks.onPlacePicked(place)
    }

    fun show() {
        if (dialog?.isShowing == true) return
        val sheet = BottomSheetDialog(context)
        dialog = sheet
        val currentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = currentScope
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_search, null)
        sheet.setContentView(root)
        sheet.setOnDismissListener {
            job?.cancel()
            currentScope.cancel()
            scope = null
            callbacks.onClosed()
        }

        val input = root.findViewById<TextInputEditText>(R.id.etSearch)
        val progress = root.findViewById<ProgressBar>(R.id.searchProgress)
        val empty = root.findViewById<TextView>(R.id.tvSearchEmpty)
        val list = root.findViewById<RecyclerView>(R.id.rvSearch)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter

        fun search() {
            val query = input.text?.toString()?.trim().orEmpty()
            if (query.length < 2) {
                empty.text = context.getString(R.string.search_hint)
                empty.isVisible = true
                return
            }
            job?.cancel()
            progress.isVisible = true
            empty.isVisible = false
            job = currentScope.launch {                try {
                    val results = NominatimClient.search(query)
                    adapter.submit(results)
                    empty.text = context.getString(R.string.search_empty)
                    empty.isVisible = results.isEmpty()
                } catch (_: Exception) {
                    adapter.submit(emptyList())
                    empty.text = context.getString(R.string.search_failed)
                    empty.isVisible = true
                } finally {
                    progress.isVisible = false
                }
            }
        }

        root.findViewById<View>(R.id.btnSearchGo).setOnClickListener { search() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else {
                false
            }
        }

        root.findViewById<ImageButton>(R.id.btnCloseSearch).setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private class PlaceAdapter(
        private val onPick: (PlaceResult) -> Unit
    ) : RecyclerView.Adapter<PlaceAdapter.Holder>() {

        private val items = ArrayList<PlaceResult>()

        fun submit(newItems: List<PlaceResult>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_result, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.name.text = item.displayName
            holder.subtitle.text = Fmt.coordinates(item.latitude, item.longitude, false)
            holder.itemView.setOnClickListener { onPick(item) }
        }

        override fun getItemCount(): Int = items.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvPlaceName)
            val subtitle: TextView = view.findViewById(R.id.tvPlaceSubtitle)
        }
    }
}
