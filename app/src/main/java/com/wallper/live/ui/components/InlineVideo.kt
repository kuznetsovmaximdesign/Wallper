package com.wallper.live.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.wallper.live.WallperApplication

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

/** One muted preview player per screen; only the focused card renders into it. */
@Composable
fun rememberPreviewPlayer(): ExoPlayer {
    val context = LocalContext.current
    val player = remember(context) { (context.applicationContext as WallperApplication).container.newPreviewPlayer() }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleResumeEffect(player) {
        player.playWhenReady = true
        onPauseOrDispose { player.playWhenReady = false }
    }
    return player
}

/**
 * Plays [uri] muted and looping inside a card. The view stays transparent until the
 * first frame is rendered, so the cover underneath never flashes black.
 */
@Composable
fun InlineVideo(player: ExoPlayer, uri: String, modifier: Modifier = Modifier, startMs: Long = 0) {
    val context = LocalContext.current
    val view = remember(context) { CropTextureView(context) }
    var rendered by remember(uri) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (rendered) 1f else 0f, tween(450), label = "inlineVideoAlpha")

    DisposableEffect(player, uri) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0) {
                    view.setVideoSize((videoSize.width * videoSize.pixelWidthHeightRatio).toInt(), videoSize.height)
                }
            }

            override fun onRenderedFirstFrame() {
                rendered = true
            }
        }
        player.addListener(listener)
        player.setVideoTextureView(view)
        val item = MediaItem.Builder().setUri(uri).apply {
            if (startMs > 0) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(startMs + PREVIEW_LOOP_MS)
                        .build(),
                )
            }
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
        onDispose {
            player.removeListener(listener)
            player.clearVideoTextureView(view)
            player.stop()
        }
    }

    AndroidView(factory = { view }, modifier = modifier.fillMaxSize().alpha(alpha))
}

private const val PREVIEW_LOOP_MS = 20_000L
