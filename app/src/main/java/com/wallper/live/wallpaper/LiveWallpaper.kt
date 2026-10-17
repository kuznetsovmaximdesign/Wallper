package com.wallper.live.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Entry points into the system live-wallpaper picker. */
object LiveWallpaper {

    fun component(context: Context) = ComponentName(context, VideoWallpaperService::class.java)

    fun isActive(context: Context): Boolean =
        WallpaperManager.getInstance(context).wallpaperInfo?.component == component(context)

    /** Opens the system preview of our wallpaper with its "Set wallpaper" button. */
    fun pickerIntent(context: Context): Intent =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context))

    fun chooserIntent(): Intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
}
