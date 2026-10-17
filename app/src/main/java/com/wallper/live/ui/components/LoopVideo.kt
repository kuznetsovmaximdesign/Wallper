package com.wallper.live.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wallper.live.WallperApplication
import com.wallper.live.core.Focus
import com.wallper.live.playback.LoopRenderer
import com.wallper.live.playback.LoopSession
import com.wallper.live.playback.LoopSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** GLSurfaceView hosting a [LoopRenderer]: the same pipeline the wallpaper uses. */
@SuppressLint("ViewConstructor")
class LoopVideoView(context: Context) : GLSurfaceView(context) {
    val renderer = LoopRenderer { requestRender() }

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}

/**
 * Live, seamlessly looping video. Dragging re-frames the shot (the same focus point is
 * used by the wallpaper on every screen size).
 */
@Composable
fun LoopVideo(
    spec: LoopSpec?,
    seamless: Boolean,
    speed: Float,
    focus: Focus,
    modifier: Modifier = Modifier,
    frostReveal: Boolean = false,
    revealKey: Any? = null,
    onFocusChange: ((Focus) -> Unit)? = null,
    /** True while a video picture is on screen; false while the surface is still black. */
    onPicture: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as WallperApplication).container }
    val view = remember(context) { LoopVideoView(context) }
    var session by remember { mutableStateOf<LoopSession?>(null) }

    // A new spec (fragment, loop setting…) gets a new session, but the current one keeps
    // playing until the new one is ready, so the picture never stops or goes black.
    LaunchedEffect(spec, seamless) {
        val created = spec?.let { container.newLoopSession(it, seamless) }
        var swapped = false
        try {
            val previous = session
            if (created != null && previous != null && spec?.highFrameRate != true) {
                withTimeoutOrNull(SWAP_WAIT_MS) { while (!created.isReady) delay(SWAP_POLL_MS) }
            }
            previous?.let {
                it.detach(view.renderer.target)
                it.release()
            }
            created?.attach(view.renderer.target)
            session = created
            swapped = true
        } finally {
            if (!swapped) created?.release()
        }
    }
    LaunchedEffect(session, speed) { session?.speed = speed }

    // Replays the frosted-glass reveal, e.g. when the preview switches lock → home.
    LaunchedEffect(revealKey, frostReveal) {
        view.renderer.revealEnabled = frostReveal
        view.renderer.reveal()
    }

    SideEffect {
        view.renderer.focusX = focus.x
        view.renderer.focusY = focus.y
        view.requestRender()
    }

    LifecycleResumeEffect(view) {
        view.onResume()
        onPauseOrDispose { view.onPause() }
    }
    LifecycleResumeEffect(session) {
        val current = session
        current?.play()
        onPauseOrDispose { current?.pause() }
    }

    val pictureCallback by rememberUpdatedState(onPicture)
    DisposableEffect(view) {
        view.renderer.onPictureChanged = { pictureCallback?.invoke(it) }
        onDispose {
            session?.let {
                it.detach(view.renderer.target)
                it.release()
            }
            session = null
            view.renderer.onPictureChanged = null
            view.renderer.release()
        }
    }

    Box(modifier.focusDrag(focus, onFocusChange)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Dragging moves the [focus] point (re-framing). Applied on a parent, it keeps working
 * while stills or loops are layered above the video.
 */
@Composable
fun Modifier.focusDrag(focus: Focus, onFocusChange: ((Focus) -> Unit)?): Modifier {
    val currentFocus by rememberUpdatedState(focus)
    val focusCallback by rememberUpdatedState(onFocusChange)
    if (onFocusChange == null) return this
    return pointerInput(Unit) {
        detectDragGestures { change, drag ->
            change.consume()
            val f = currentFocus
            focusCallback?.invoke(
                Focus(
                    x = (f.x - drag.x / size.width * DRAG_GAIN).coerceIn(0f, 1f),
                    y = (f.y - drag.y / size.height * DRAG_GAIN).coerceIn(0f, 1f),
                ),
            )
        }
    }
}

private const val DRAG_GAIN = 1.6f
private const val SWAP_WAIT_MS = 1_500L
private const val SWAP_POLL_MS = 25L
