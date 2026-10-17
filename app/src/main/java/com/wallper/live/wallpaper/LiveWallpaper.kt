package com.wallper.live.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wallper.live.core.WallpaperTarget

/** Entry points into the system live-wallpaper picker. */
object LiveWallpaper {

    fun component(context: Context) = ComponentName(context, VideoWallpaperService::class.java)

    /** Our wallpaper is set on the home screen. */
    fun isActive(context: Context): Boolean =
        WallpaperManager.getInstance(context).wallpaperInfo?.component == component(context)

    /** Our wallpaper is already where [target] needs it, so no system picker is required. */
    fun covers(context: Context, target: WallpaperTarget): Boolean {
        val home = isActive(context)
        val lock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val info = WallpaperManager.getInstance(context).getWallpaperInfo(WallpaperManager.FLAG_LOCK)
            // No separate lock wallpaper: the lock screen shows the home one.
            if (info == null) home else info.component == component(context)
        } else {
            home
        }
        return when (target) {
            WallpaperTarget.Both -> home && lock
            WallpaperTarget.Home -> home
            WallpaperTarget.Lock -> lock
        }
    }

    /** Opens the system preview of our wallpaper with its "Set wallpaper" button. */
    fun pickerIntent(context: Context): Intent =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context))

    fun chooserIntent(): Intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
}
