package com.wallper.live.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Plays [spec] muted and looping inside a card.
 * The view stays transparent until the first frame, so the cover never flashes black.
 * A failed player is rebuilt with backoff and right after a network (VPN) switch.
 */
@Composable
fun InlineVideo(spec: LoopSpec, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as WallperApplication).container }
    val factory = container.loopPlayers
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(context) { CropTextureView(context) }
    var rendered by remember(spec) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (rendered) 1f else 0f, tween(450), label = "inlineVideoAlpha")
    var attempt by remember(spec) { mutableIntStateOf(0) }
    var failed by remember(spec) { mutableStateOf(false) }

    LaunchedEffect(spec, failed) {
        if (!failed) return@LaunchedEffect
        delay(minOf(RETRY_BASE_MS shl minOf(attempt, 3), RETRY_MAX_MS))
        failed = false
        attempt++
    }
    // Decoders are held only while the app is on screen: in the background they would
    // compete with the live wallpaper and leave nothing for the return.
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val onScreen = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    // Players start one after another, so a screenful of cards does not grab every
    // decoder in the same instant (that is when frames drop).
    var cleared by remember(spec) { mutableStateOf(false) }
    LaunchedEffect(spec, onScreen) {
        cleared = false
        if (!onScreen) return@LaunchedEffect
        StartGate.await()
        cleared = true
    }
    LaunchedEffect(spec) {
        container.network.changes.drop(1).collect {
            if (failed) {
                failed = false
                attempt++
            }
        }
    }

    DisposableEffect(spec, lifecycle, attempt, cleared) {
        if (!cleared) {
            rendered = false
            return@DisposableEffect onDispose {}
        }
        val player = factory.create(spec)
        player.repeat = true
        player.listener = object : LoopPlayer.Listener {
            override fun onVideoSize(width: Int, height: Int, pixelRatio: Float) {
                view.setVideoSize((width * pixelRatio).toInt(), height)
            }

            override fun onFirstFrame() {
                rendered = true
            }

            override fun onError(error: Exception) {
                failed = true
            }
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

/** Spaces out the start of card players. */
private object StartGate {
    private val mutex = Mutex()
    private var lastStart = 0L

    suspend fun await() = mutex.withLock {
        val wait = lastStart + START_GAP_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        lastStart = SystemClock.elapsedRealtime()
    }
}

private const val START_GAP_MS = 180L
private const val RETRY_BASE_MS = 1_500L
private const val RETRY_MAX_MS = 12_000L
