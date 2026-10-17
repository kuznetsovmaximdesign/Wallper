package com.wallper.live.playback

import android.view.Choreographer

/**
 * Plays a clip as an endless loop.
 *
 * Seamless mode uses two players on the same media. The clip holds [LoopSpec.loopMs] of
 * picture plus a [LoopSpec.fadeMs] tail: when the front player reaches the loop point
 * the back player starts from 0 and the renderer cross-fades to it, so the seam is
 * invisible. Roles then swap. If a second decoder is unavailable the session falls
 * back to a single repeating player.
 *
 * Decoder output surfaces belong to the attached [VideoTarget]; switching targets
 * (e.g. folding/unfolding a phone moves the wallpaper to another display) only swaps
 * output surfaces, so playback continues on the very same frame.
 *
 * Must be used on the main thread.
 */
class LoopSession(
    factory: LoopPlayerFactory,
    private val spec: LoopSpec,
    seamless: Boolean,
    startPositionMs: Long = 0,
) {
    private val players = ArrayList<LoopPlayer>(2)
    private var front = 0
    private var fading = false
    private var mix = 0f
    private var playing = false
    private var ticking = false
    private var released = false
    private var videoSize: Triple<Int, Int, Float>? = null
    private var target: VideoTarget? = null

    private val fadeEnabled get() = players.size == 2

    var speed: Float = 1f
        set(value) {
            field = value
            players.forEach { it.speed = value }
        }

    val positionMs: Long get() = players.getOrNull(front)?.positionMs ?: 0

    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            tick()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        val count = if (seamless && spec.fadeMs > 0) 2 else 1
        repeat(count) {
            val player = factory.create(spec)
            player.repeat = count == 1
            player.listener = PlayerListener(player)
            players += player
        }
        if (startPositionMs > 0) players[0].seekToStart(startPositionMs.coerceIn(0, spec.loopMs))
    }

    fun attach(newTarget: VideoTarget) {
        if (released || target === newTarget) return
        target?.onSurfacesChanged = null
        target = newTarget
        newTarget.onSurfacesChanged = { bindSurfaces() }
        bindSurfaces()
        videoSize?.let { (w, h, ratio) -> newTarget.setVideoSize(w, h, ratio) }
        newTarget.present(front, mix)
    }

    fun detach(oldTarget: VideoTarget) {
        if (target !== oldTarget) return
        oldTarget.onSurfacesChanged = null
        target = null
        players.forEach { it.setSurface(null) }
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
        players.forEachIndexed { index, player -> player.setSurface(surfaces?.getOrNull(index)) }
    }

    private fun tick() {
        if (!fadeEnabled) return
        val current = players[front]
        val next = players[1 - front]
        if (!fading) {
            val reachedLoop = current.positionMs >= spec.loopMs || current.isEnded
            if (!reachedLoop) return
            if (next.isReady) {
                next.play()
                fading = true
            } else if (current.isEnded) {
                current.seekToStart() // the back player is not ready: hard loop this time
                if (playing) current.play()
            }
            return
        }
        mix = (next.positionMs.toFloat() / spec.fadeMs).coerceIn(0f, 1f)
        if (mix >= 1f || next.isEnded) {
            current.pause()
            current.seekToStart()
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

    /** Drops the second decoder and loops the remaining player on its own. */
    private fun fallBackToSinglePlayer(failed: LoopPlayer) {
        if (!fadeEnabled) return
        stopTicking()
        val survivor = players.first { it !== failed }
        failed.release()
        players.clear()
        players += survivor
        front = 0
        fading = false
        mix = 0f
        survivor.repeat = true
        bindSurfaces()
        target?.present(0, 0f)
        if (playing) survivor.play()
    }

    private inner class PlayerListener(private val player: LoopPlayer) : LoopPlayer.Listener {
        override fun onVideoSize(width: Int, height: Int, pixelRatio: Float) {
            videoSize = Triple(width, height, pixelRatio)
            target?.setVideoSize(width, height, pixelRatio)
        }

        override fun onError(error: Exception) {
            if (released) return
            if (fadeEnabled) {
                fallBackToSinglePlayer(player)
            } else {
                player.seekToStart()
                if (playing) player.play()
            }
        }
    }
}
