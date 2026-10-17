package com.wallper.live.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wallper.live.WallperApplication
import com.wallper.live.playback.LoopPlayer
import com.wallper.live.playback.LoopSpec

/** TextureView that center-crops its video, so it can live inside rounded, clipped cards. */
@SuppressLint("ViewConstructor")
class CropTextureView(context: Context) : TextureView(context) {
    private var videoWidth = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        applyCrop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCrop()
    }

    private fun applyCrop() {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return
        val videoAspect = videoWidth.toFloat() / videoHeight
        val viewAspect = width.toFloat() / height
        val matrix = Matrix()
        if (videoAspect > viewAspect) {
            matrix.setScale(videoAspect / viewAspect, 1f, width / 2f, height / 2f)
        } else {
            matrix.setScale(1f, viewAspect / videoAspect, width / 2f, height / 2f)
        }
        setTransform(matrix)
    }
}

/**
 * Plays [spec] muted and looping inside a card (only the focused card mounts one).
 * The view stays transparent until the first frame, so the cover never flashes black.
 */
@Composable
fun InlineVideo(spec: LoopSpec, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val factory = remember(context) { (context.applicationContext as WallperApplication).container.loopPlayers }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(context) { CropTextureView(context) }
    var rendered by remember(spec) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (rendered) 1f else 0f, tween(450), label = "inlineVideoAlpha")

    DisposableEffect(spec, lifecycle) {
        val player = factory.create(spec)
        player.repeat = true
        player.listener = object : LoopPlayer.Listener {
            override fun onVideoSize(width: Int, height: Int, pixelRatio: Float) {
                view.setVideoSize((width * pixelRatio).toInt(), height)
            }

            override fun onFirstFrame() {
                rendered = true
            }

            override fun onError(error: Exception) = Unit
        }
        var surface: Surface? = null
        fun attach(texture: SurfaceTexture) {
            surface?.release()
            surface = Surface(texture).also(player::setSurface)
        }
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = attach(texture)
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                player.setSurface(null)
                surface?.release()
                surface = null
                return true
            }
        }
        view.surfaceTexture?.let(::attach)

        // Only decode while the screen is in front.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> player.play()
                Lifecycle.Event.ON_PAUSE -> player.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player.play()

        onDispose {
            lifecycle.removeObserver(observer)
            view.surfaceTextureListener = null
            player.release()
            surface?.release()
        }
    }

    AndroidView(factory = { view }, modifier = modifier.fillMaxSize().alpha(alpha))
}
