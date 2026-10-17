package com.wallper.live.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Which panel of a book-style foldable a surface is on. Regular phones are [Regular]. */
enum class PanelKind { Regular, Cover, Inner }

object Foldables {

    /** Book-style foldables (Galaxy Z Fold, Pixel Fold, OnePlus Open…) expose a hinge sensor. */
    fun isFoldable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

    /**
     * The inner panel is nearly square (≈ 5:6), the cover panel tall and narrow (≈ 9:21),
     * so the aspect ratio tells them apart in any orientation.
     */
    fun classify(width: Int, height: Int, foldable: Boolean): PanelKind {
        if (!foldable || width <= 0 || height <= 0) return PanelKind.Regular
        val ratio = minOf(width, height).toFloat() / maxOf(width, height)
        return if (ratio >= INNER_MIN_RATIO) PanelKind.Inner else PanelKind.Cover
    }

    private const val INNER_MIN_RATIO = 0.7f
}
