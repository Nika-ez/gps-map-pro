package com.nika.gpsmap

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.core.content.ContextCompat

data class Palette(
    val mode: ThemeMode,
    val panel: Int,
    val text: Int,
    val subtle: Int,
    val extra: Int,
    val accent: Int,
    val sat: Int,
    val warn: Int,
    val danger: Int,
    val handle: Int,
    val mapDim: Int,
    val statusBar: Int,
    val fabBg: Int,
    val fabIcon: Int,
    val accentInk: Int,
    val isLight: Boolean
) {
    companion object {
        fun from(context: Context, mode: ThemeMode): Palette {
            fun color(attr: Int, fallback: Int): Int {
                val value = TypedValue()
                return if (context.theme.resolveAttribute(attr, value, true)) {
                    if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
                } else {
                    fallback
                }
            }

            return Palette(
                mode = mode,
                panel = color(R.attr.hudPanelColor, Color.parseColor("#E6121212")),
                text = color(R.attr.hudTextColor, Color.WHITE),
                subtle = color(R.attr.hudSubtleColor, Color.parseColor("#AAAAAA")),
                extra = color(R.attr.hudExtraColor, Color.parseColor("#99FFFFFF")),
                accent = color(R.attr.hudAccentColor, Color.parseColor("#32D74B")),
                sat = color(R.attr.hudSatColor, Color.parseColor("#0A84FF")),
                warn = color(R.attr.hudWarnColor, Color.parseColor("#FFD60A")),
                danger = color(R.attr.hudDangerColor, Color.parseColor("#FF453A")),
                handle = color(R.attr.hudHandleColor, Color.parseColor("#80FFFFFF")),
                mapDim = color(R.attr.mapDimColor, Color.TRANSPARENT),
                statusBar = color(R.attr.appStatusBarColor, Color.BLACK),
                fabBg = color(R.attr.appFabColor, Color.parseColor("#CC333333")),
                fabIcon = color(R.attr.appFabIconColor, Color.WHITE),
                accentInk = color(R.attr.appAccentInk, Color.WHITE),
                isLight = mode == ThemeMode.LIGHT
            )
        }
    }
}
