package com.wallper.live.wallpaper

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import com.wallper.live.WallperApplication
import com.wallper.live.core.Foldables
import com.wallper.live.core.PanelKind
import com.wallper.live.core.WallpaperRole
import com.wallper.live.core.WallpaperRoles
import com.wallper.live.playback.LoopRenderer
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    private companion object {
        /** Shared by the cover and inner engines: where the hinge was last seen. */
        @Volatile var lastHingeAngle: Float? = null
    }

    private inner class VideoEngine : Engine() {

        private val container = (application as WallperApplication).container
        private val scope = MainScope()
        private var glView: EngineGlSurfaceView? = null
        private val renderer = LoopRenderer { glView?.requestRender() }

        private val role = MutableStateFlow(WallpaperRole.Home)

        @Volatile private var coverFile: File? = null

        private val foldable = Foldables.isFoldable(this@VideoWallpaperService)
        private val sensors = getSystemService(SensorManager::class.java)
        private var hingeRegistered = false

        private val hingeListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val angle = event.values[0]
                lastHingeAngle = angle
                // A reading between the stops proves a fine sensor. (Exactly 0° or 180° proves
                // nothing: every sensor reports those when shut or flat.)
                if (angle % 90f != 0f) renderer.hingeCoarse = false
                renderer.setHingeAngle(angle)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        private val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (isVisible) renderer.reveal()
            }
        }

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
            // Unlocking keeps the same engine visible before Android 14: reveal on unlock too.
            ContextCompat.registerReceiver(
                this@VideoWallpaperService,
                unlockReceiver,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            scope.launch {
                combine(container.settings.settings, role) { s, on -> s to on }.collect { (s, on) ->
                    val focus = s.clipIdFor(on)?.let(s::focusOf)
                    renderer.focusX = focus?.x ?: 0.5f
                    renderer.focusY = focus?.y ?: 0.5f
                    renderer.parallaxEnabled = s.parallax
                    renderer.revealEnabled = s.frostReveal
                    glView?.requestRender()
                }
            }
            scope.launch {
                combine(container.settings.settings, container.library.clips, role) { s, clips, on ->
                    clips.firstOrNull { it.id == s.clipIdFor(on) }?.let(container.library::coverOf)
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
            refreshRole()
            renderer.panel = Foldables.classify(width, height, foldable)
            // The crease is vertical in the display's natural orientation (Fold, Pixel Fold).
            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                displayContext?.display?.rotation ?: Surface.ROTATION_0
            } else {
                Surface.ROTATION_0
            }
            renderer.creaseVertical = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            glView?.surfaceDestroyed(holder)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                // The inner panel follows the hinge as it opens. The cover panel lights up
                // when the phone is all but shut, after the hinge has stopped: it shows the
                // glass clearing, like a regular phone waking up.
                if (renderer.panel != PanelKind.Inner) renderer.reveal()
                setHingeTracking(true)
                glView?.onResume()
                refreshRole()
                container.playbackHub.show(renderer.target, role.value)
            } else {
                container.playbackHub.hide(renderer.target)
                setHingeTracking(false)
                glView?.onPause()
            }
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            refreshRole()
        }

        /** Follows the hinge only while visible on a foldable (the sensor costs power). */
        private fun setHingeTracking(enabled: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            if (!foldable || enabled == hingeRegistered) return
            val sensor = sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) ?: return
            hingeRegistered = if (enabled) {
                renderer.hingeCoarse = sensor.resolution >= 30f
                // A panel that lights up mid-unfold starts from the last known angle
                // (frosted), never from a sharp picture that then jumps. A silent sensor
                // simply lets the glass clear once the hinge counts as still.
                if (renderer.panel == PanelKind.Inner) renderer.snapHinge(lastHingeAngle ?: 60f)
                sensors.registerListener(hingeListener, sensor, SensorManager.SENSOR_DELAY_GAME)
            } else {
                sensors.unregisterListener(hingeListener)
                false
            }
        }

        /** Home or lock screen? Only Android 14+ can tell (and only it can differ). */
        private fun refreshRole() {
            val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WallpaperRoles.fromFlags(wallpaperFlags)
            } else {
                WallpaperRole.Home
            }
            if (next == role.value) return
            role.value = next
            if (isVisible) container.playbackHub.updateRole(renderer.target, next)
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
            runCatching { unregisterReceiver(unlockReceiver) }
            setHingeTracking(false)
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
