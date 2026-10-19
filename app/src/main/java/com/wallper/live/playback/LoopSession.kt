package com.wallper.live.playback

import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private val factory: LoopPlayerFactory,
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
    private val main = Handler(Looper.getMainLooper())
    private var failures = 0

    var speed: Float = 1f
        set(value) {
            field = value
            players.forEach { it.speed = value }
        }

    val positionMs: Long get() = players.getOrNull(front)?.positionMs ?: 0

    /** The front player has buffered enough to show its first picture. */
    val isReady: Boolean get() = players.getOrNull(front)?.isReady == true

    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            tick()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Never frozen: if pictures stop arriving while playing (a decoder that lost its
     * output after a surface change, a stalled stream), the output path is rebuilt and
     * playback resumes where it was. A new target (e.g. after rotating) that never gets
     * its first picture counts too.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (released || !playing) return
            val now = System.nanoTime()
            val current = target
            val since = maxOf(current?.lastFrameNanos ?: 0L, watchingSince)
            val player = players.getOrNull(front)
            if (current?.surfaces != null && player?.isReady == true && now - since > STALL_NANOS) {
                Log.w(TAG, "no picture for ${(now - since) / 1_000_000} ms while playing: recovering")
                watchingSince = now
                players.forEach { it.setSurface(null) }
                bindSurfaces()
                players.getOrNull(1 - front)?.let {
                    it.pause()
                    it.seekToStart()
                }
                player.seekToStart(player.positionMs.coerceIn(0, spec.loopMs))
                player.play()
                fading = false
                mix = 0f
                target?.present(front, 0f)
                main.postDelayed(this, STALL_NANOS / 1_000_000 * 2) // give it time to come back
                return
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    /** [System.nanoTime] since when pictures are expected: playing started or the target changed. */
    private var watchingSince = 0L

    /** The cross-fade partner is created once the front player shows its first picture. */
    private var wantsFade = seamless && spec.fadeMs > 0

    init {
        // One decoder first: the picture appears sooner when it does not share the
        // network and the hardware with the second one.
        val player = factory.create(spec)
        player.repeat = !wantsFade
        player.listener = PlayerListener(player)
        players += player
        if (startPositionMs > 0) player.seekToStart(startPositionMs.coerceIn(0, spec.loopMs))
    }

    private fun addFadePartner() {
        if (!wantsFade || released || players.size != 1) return
        wantsFade = false
        val partner = factory.create(spec)
        partner.listener = PlayerListener(partner)
        players += partner
        bindSurfaces()
        if (playing) startTicking()
    }

    fun attach(newTarget: VideoTarget) {
        if (released || target === newTarget) return
        target?.onSurfacesChanged = null
        target = newTarget
        newTarget.onSurfacesChanged = { bindSurfaces() }
        watchingSince = System.nanoTime()
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
        if (!playing) watchingSince = System.nanoTime()
        playing = true
        players[front].play()
        if (fading) players[1 - front].play()
        startTicking()
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun pause() {
        playing = false
        players.forEach { it.pause() }
        stopTicking()
        main.removeCallbacks(watchdog)
    }

    fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
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

        override fun onFirstFrame() {
            failures = 0
            if (player === players.getOrNull(0)) addFadePartner()
        }

        override fun onError(error: Exception) {
            if (released) return
            if (fadeEnabled) {
                fallBackToSinglePlayer(player)
            } else {
                // Usually the network (e.g. a VPN going up or down): retry with backoff.
                val delayMs = minOf(RETRY_BASE_MS shl minOf(failures, 3), RETRY_MAX_MS)
                failures++
                main.postDelayed({
                    if (!released && player in players) {
                        player.seekToStart()
                        if (playing) player.play()
                    }
                }, delayMs)
            }
        }
    }

    private companion object {
        const val TAG = "WallperSession"
        const val WATCHDOG_MS = 1_000L
        const val STALL_NANOS = 3_000_000_000L
        const val RETRY_BASE_MS = 1_000L
        const val RETRY_MAX_MS = 8_000L
    }
}
