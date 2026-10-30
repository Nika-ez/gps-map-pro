package com.nika.gpsmap.ui

import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Bottom sheets occasionally open in their collapsed peek state (most visibly
 * in landscape), leaving only the header visible until the user drags them up.
 * Always start expanded so the content is immediately usable.
 */
internal fun BottomSheetDialog.showExpanded() {
    setOnShowListener {
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
    show()
}
