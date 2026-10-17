package com.wallper.live.wallpaper

import android.app.WallpaperColors
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.wallper.live.WallperApplication
import com.wallper.live.playback.LoopRenderer
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine() {

        private val container = (application as WallperApplication).container
        private val scope = MainScope()
        private var glView: EngineGlSurfaceView? = null
        private val renderer = LoopRenderer { glView?.requestRender() }

        @Volatile private var coverFile: File? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(true)
            glView = EngineGlSurfaceView(this@VideoWallpaperService).apply {
                engineHolder = surfaceHolder
                setEGLContextClientVersion(3)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
            container.playbackHub.register(this)

            scope.launch {
                container.settings.settings.collect { s ->
                    val focus = s.activeClipId?.let(s::focusOf)
                    renderer.focusX = focus?.x ?: 0.5f
                    renderer.focusY = focus?.y ?: 0.5f
                    renderer.parallaxEnabled = s.parallax
                    glView?.requestRender()
                }
            }
            scope.launch {
                combine(container.settings.settings, container.library.clips) { s, clips ->
                    clips.firstOrNull { it.id == s.activeClipId }?.let(container.library::coverOf)
                }.map { it?.path }.distinctUntilChanged().collect { path ->
                    coverFile = path?.let(::File)
                    notifyColorsChanged()
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            glView?.surfaceCreated(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            glView?.surfaceChanged(holder, format, width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            glView?.surfaceDestroyed(holder)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                glView?.onResume()
                container.playbackHub.show(renderer.target)
            } else {
                container.playbackHub.hide(renderer.target)
                glView?.onPause()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            renderer.setParallax(xOffset - 0.5f)
        }

        /** Lets Material You theme the system UI from the actual video frame. */
        override fun onComputeColors(): WallpaperColors? {
            val file = coverFile?.takeIf { it.exists() } ?: return null
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
            return try {
                WallpaperColors.fromBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        override fun onDestroy() {
            scope.cancel()
            container.playbackHub.forget(renderer.target)
            container.playbackHub.unregister(this)
            renderer.release()
            glView?.release()
            glView = null
            super.onDestroy()
        }
    }
}
