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
    spec: LoopSession.LoopSpec?,
    seamless: Boolean,
    speed: Float,
    focus: Focus,
    modifier: Modifier = Modifier,
    onFocusChange: ((Focus) -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as WallperApplication).container }
    val view = remember(context) { LoopVideoView(context) }
    var session by remember { mutableStateOf<LoopSession?>(null) }

    DisposableEffect(spec, seamless) {
        val created = spec?.let { container.newLoopSession(it, seamless) }
        created?.attach(view.renderer.target)
        session = created
        onDispose {
            created?.detach(view.renderer.target)
            created?.release()
        }
    }

    LaunchedEffect(session, speed) { session?.speed = speed }

    SideEffect {
        view.renderer.focusX = focus.x
        view.renderer.focusY = focus.y
        view.requestRender()
    }

    LifecycleResumeEffect(view, session) {
        view.onResume()
        session?.play()
        onPauseOrDispose {
            session?.pause()
            view.onPause()
        }
    }

    DisposableEffect(view) {
        onDispose { view.renderer.release() }
    }

    val currentFocus by rememberUpdatedState(focus)
    val focusCallback by rememberUpdatedState(onFocusChange)
    val dragModifier = if (onFocusChange == null) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
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

    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().then(dragModifier))
    }
}

private const val DRAG_GAIN = 1.6f
