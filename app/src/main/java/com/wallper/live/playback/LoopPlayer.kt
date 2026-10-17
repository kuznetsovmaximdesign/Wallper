package com.wallper.live.playback

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import okhttp3.OkHttpClient

/** What a [LoopSession] (or an inline preview) plays: a window of one video. */
data class LoopSpec(
    val uri: Uri,
    val loopMs: Long,
    val fadeMs: Long,
    /** Window inside the source; 0 / null play the whole file. */
    val clipStartMs: Long = 0,
    val clipEndMs: Long? = null,
    /** Source above 60 fps with HEVC temporal layers (Apple's 240 fps Aerials). */
    val highFrameRate: Boolean = false,
    /** Upper bound for the frame rate kept from a [highFrameRate] source. */
    val maxFps: Double = 60.0,
)

/**
 * Minimal player contract used by [LoopSession]. Positions are relative to the
 * window start. Main thread only.
 */
interface LoopPlayer {
    interface Listener {
        fun onVideoSize(width: Int, height: Int, pixelRatio: Float)
        fun onFirstFrame() {}
        fun onError(error: Exception)
    }

    var listener: Listener?
    var speed: Float

    /** Restart from the window start automatically when the end is reached. */
    var repeat: Boolean
    val positionMs: Long
    val isReady: Boolean
    val isEnded: Boolean

    fun setSurface(surface: Surface?)
    fun play()
    fun pause()
    fun seekToStart(positionMs: Long = 0)
    fun release()
}

/** Creates the right player for a spec: ExoPlayer, or the layer-dropping decoder. */
@OptIn(UnstableApi::class)
class LoopPlayerFactory(
    private val context: Context,
    private val mediaSourceFactory: MediaSource.Factory,
    private val http: OkHttpClient,
) {
    fun create(spec: LoopSpec): LoopPlayer =
        if (spec.highFrameRate) CodecLoopPlayer(context, http, spec) else ExoLoopPlayer(context, mediaSourceFactory, spec)
}

/** [LoopPlayer] backed by ExoPlayer, for regular-frame-rate sources. */
@OptIn(UnstableApi::class)
private class ExoLoopPlayer(
    context: Context,
    mediaSourceFactory: MediaSource.Factory,
    spec: LoopSpec,
) : LoopPlayer {

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setMediaSourceFactory(mediaSourceFactory)
        .build()

    override var listener: LoopPlayer.Listener? = null

    override var speed: Float = 1f
        set(value) {
            field = value
            player.setPlaybackSpeed(value)
        }

    override var repeat: Boolean = false
        set(value) {
            field = value
            player.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    override val positionMs: Long get() = player.currentPosition
    override val isReady: Boolean get() = player.playbackState == Player.STATE_READY
    override val isEnded: Boolean get() = player.playbackState == Player.STATE_ENDED

    init {
        player.volume = 0f
        val item = MediaItem.Builder().setUri(spec.uri).apply {
            if (spec.clipStartMs > 0 || spec.clipEndMs != null) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(spec.clipStartMs)
                        .apply { spec.clipEndMs?.let { setEndPositionMs(it) } }
                        .build(),
                )
            }
        }.build()
        player.setMediaItem(item)
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    listener?.onVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
                }
            }

            override fun onRenderedFirstFrame() {
                listener?.onFirstFrame()
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error)
            }
        })
        player.prepare()
    }

    override fun setSurface(surface: Surface?) {
        if (surface != null) player.setVideoSurface(surface) else player.clearVideoSurface()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekToStart(positionMs: Long) {
        if (player.playerError != null) player.prepare()
        player.seekTo(positionMs)
    }
    override fun release() = player.release()
}
