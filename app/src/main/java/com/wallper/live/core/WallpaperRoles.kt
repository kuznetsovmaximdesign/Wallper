package com.wallper.live.core

import android.app.WallpaperManager
import android.os.Build

/** Where a wallpaper engine is drawing. */
enum class WallpaperRole { Home, Lock }

/** Where the user wants a wallpaper. */
enum class WallpaperTarget { Both, Home, Lock }

object WallpaperRoles {

    /** Android 14+ lets a live wallpaper differ between the home and the lock screen. */
    val separateLockSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Maps WallpaperManager.FLAG_* of an engine to the role it renders. */
    fun fromFlags(flags: Int): WallpaperRole =
        if (flags and WallpaperManager.FLAG_SYSTEM == 0 && flags and WallpaperManager.FLAG_LOCK != 0) {
            WallpaperRole.Lock
        } else {
            WallpaperRole.Home
        }
}
