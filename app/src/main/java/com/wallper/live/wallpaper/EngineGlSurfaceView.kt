package com.wallper.live.wallpaper

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.SurfaceHolder

/**
 * GLSurfaceView that renders into a wallpaper engine's surface instead of its own.
 * The engine forwards its surface callbacks; the view is never attached to a window.
 */
class EngineGlSurfaceView(context: Context) : GLSurfaceView(context) {

    var engineHolder: SurfaceHolder? = null

    override fun getHolder(): SurfaceHolder = engineHolder ?: super.getHolder()

    /** Stops the GL thread (the view is never detached from a window otherwise). */
    fun release() = onDetachedFromWindow()
}
