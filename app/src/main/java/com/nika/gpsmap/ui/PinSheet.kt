package com.nika.gpsmap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.nika.gpsmap.Fmt
import com.nika.gpsmap.Prefs
import com.nika.gpsmap.R
import com.nika.gpsmap.net.NominatimClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PinSheet(
    private val context: Context,
    private val prefs: Prefs,
    private val latitude: Double,
    private val longitude: Double,
    private val knownAddress: String?,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onRemovePin()
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
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_pin, null)
        sheet.setContentView(root)
        sheet.setOnDismissListener {
            job?.cancel()
            currentScope.cancel()
            scope = null
            callbacks.onClosed()
        }

        root.findViewById<ImageButton>(R.id.btnClosePin).setOnClickListener { sheet.dismiss() }

        val coordinates = Fmt.coordinates(latitude, longitude, prefs.coordDms)
        root.findViewById<TextView>(R.id.tvPinCoords).text = coordinates
        val addressView = root.findViewById<TextView>(R.id.tvPinAddress)

        val address = knownAddress?.takeIf { it.isNotBlank() }
        if (address != null) {
            addressView.text = address
        } else {
            addressView.text = context.getString(R.string.pin_address_loading)
            job = currentScope.launch {
                val resolved = runCatching {
                    NominatimClient.reverse(latitude, longitude)
                }.getOrNull()
                addressView.text = resolved ?: context.getString(R.string.no_address)
            }
        }

        val osmUrl = "https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude" +
            "#map=17/$latitude/$longitude"

        root.findViewById<MaterialButton>(R.id.btnCopyPin).setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GPS Map Pro pin", coordinates))
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        root.findViewById<MaterialButton>(R.id.btnSharePin).setOnClickListener {
            val text = context.getString(
                R.string.pin_share_text,
                coordinates,
                osmUrl
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.dropped_pin))
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.share_location)))
        }

        root.findViewById<MaterialButton>(R.id.btnOpenPin).setOnClickListener {
            val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }.onFailure {
                Toast.makeText(context, R.string.no_address, Toast.LENGTH_SHORT).show()
            }
        }

        root.findViewById<MaterialButton>(R.id.btnRemovePin).setOnClickListener {
            sheet.dismiss()
            callbacks.onRemovePin()
        }

        sheet.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
