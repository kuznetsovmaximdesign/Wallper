package com.wallper.live.playback

import android.content.Context
import android.view.Choreographer
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource

/**
 * Plays a clip as an endless loop.
 *
 * Seamless mode uses two players on the same media. The clip holds [LoopSpec.loopMs] of
 * picture plus a [LoopSpec.fadeMs] tail: when the front player reaches the loop point
 * the back player starts from 0 and the renderer cross-fades to it, so the seam is
 * invisible. Roles then swap. If a second decoder is unavailable the session falls
 * back to a single player with gapless repeat.
 *
 * Decoder output surfaces belong to the attached [VideoTarget]; switching targets
 * (e.g. folding/unfolding a phone moves the wallpaper to another display) only swaps
 * output surfaces, so playback continues on the very same frame.
 *
 * Must be used on the main thread.
 */
@OptIn(UnstableApi::class)
class LoopSession(
    context: Context,
    mediaSourceFactory: MediaSource.Factory,
    private val spec: LoopSpec,
    seamless: Boolean,
    startPositionMs: Long = 0,
) {
    data class LoopSpec(
        val mediaItem: MediaItem,
        val loopMs: Long,
        val fadeMs: Long,
    )

    private val players = ArrayList<ExoPlayer>(2)
    private var front = 0
    private var fading = false
    private var mix = 0f
    private var playing = false
    private var ticking = false
    private var released = false
    private var videoSize: VideoSize = VideoSize.UNKNOWN
    private var target: VideoTarget? = null

    private val fadeEnabled get() = players.size == 2

    var speed: Float = 1f
        set(value) {
            field = value
            players.forEach { it.setPlaybackSpeed(value) }
        }

    val positionMs: Long get() = players.getOrNull(front)?.currentPosition ?: 0

    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            tick()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        val count = if (seamless && spec.fadeMs > 0) 2 else 1
        repeat(count) { index ->
            val player = ExoPlayer.Builder(context)
                .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
            player.volume = 0f
            player.repeatMode = if (count == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.setMediaItem(spec.mediaItem)
            player.addListener(PlayerListener(index))
            player.prepare()
            if (index == 0 && startPositionMs > 0) player.seekTo(startPositionMs.coerceIn(0, spec.loopMs))
            players += player
        }
    }

    fun attach(newTarget: VideoTarget) {
        if (released || target === newTarget) return
        target?.onSurfacesChanged = null
        target = newTarget
        newTarget.onSurfacesChanged = { bindSurfaces() }
        bindSurfaces()
        if (videoSize != VideoSize.UNKNOWN) {
            newTarget.setVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
        }
        newTarget.present(front, mix)
    }

    fun detach(oldTarget: VideoTarget) {
        if (target !== oldTarget) return
        oldTarget.onSurfacesChanged = null
        target = null
        players.forEach { it.clearVideoSurface() }
    }

    fun play() {
        if (released) return
        playing = true
        players[front].play()
        if (fading) players[1 - front].play()
        startTicking()
    }

    fun pause() {
        playing = false
        players.forEach { it.pause() }
        stopTicking()
    }

    fun release() {
        if (released) return
        released = true
        stopTicking()
        target?.onSurfacesChanged = null
        target = null
        players.forEach { it.release() }
        players.clear()
    }

    private fun bindSurfaces() {
        val surfaces = target?.surfaces
        players.forEachIndexed { index, player ->
            val surface = surfaces?.getOrNull(index)
            if (surface != null) player.setVideoSurface(surface) else player.clearVideoSurface()
        }
    }

    private fun tick() {
        if (!fadeEnabled) return
        val current = players[front]
        val next = players[1 - front]
        if (!fading) {
            val reachedLoop = current.currentPosition >= spec.loopMs || current.playbackState == Player.STATE_ENDED
            if (!reachedLoop) return
            if (next.playbackState == Player.STATE_READY) {
                next.play()
                fading = true
            } else if (current.playbackState == Player.STATE_ENDED) {
                current.seekTo(0) // the back player is not ready: hard loop this time
            }
            return
        }
        mix = (next.currentPosition.toFloat() / spec.fadeMs).coerceIn(0f, 1f)
        if (mix >= 1f || next.playbackState == Player.STATE_ENDED) {
            current.pause()
            current.seekTo(0)
            front = 1 - front
            fading = false
            mix = 0f
        }
        target?.present(front, mix)
    }

    private fun startTicking() {
        if (ticking || !fadeEnabled) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(ticker)
    }

    private fun stopTicking() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(ticker)
    }

    /** Drops the second decoder and loops the remaining player gaplessly. */
    private fun fallBackToSinglePlayer(failed: Int) {
        if (!fadeEnabled) return
        stopTicking()
        val survivor = players[1 - failed]
        players[failed].release()
        players.clear()
        players += survivor
        front = 0
        fading = false
        mix = 0f
        survivor.repeatMode = Player.REPEAT_MODE_ONE
        bindSurfaces()
        target?.present(0, 0f)
        if (playing) survivor.play()
    }

    private inner class PlayerListener(private val index: Int) : Player.Listener {
        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width <= 0 || size.height <= 0) return
            videoSize = size
            target?.setVideoSize(size.width, size.height, size.pixelWidthHeightRatio)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (released) return
            val position = players.indexOfFirst { it.playerError === error }.takeIf { it >= 0 } ?: index
            if (fadeEnabled) {
                fallBackToSinglePlayer(position)
            } else {
                players.firstOrNull()?.let {
                    it.prepare()
                    if (playing) it.play()
                }
            }
        }
    }
}
