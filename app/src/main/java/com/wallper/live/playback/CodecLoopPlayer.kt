package com.wallper.live.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.wallper.live.media.HevcTemporalLayers
import com.wallper.live.media.RemoteFiles

/**
 * A small MediaCodec player for high-frame-rate HEVC (Apple's 4K 240 fps Aerials).
 *
 * ExoPlayer would hand every picture to the decoder, which no phone can sustain at
 * 4K 240 fps. This player drops pictures of the upper temporal layers *before*
 * decoding (lossless: no kept picture references a dropped one), so a 240 fps master
 * plays at 60 or 30 fps straight from Apple's CDN — the live preview before install.
 *
 * Public methods are called on the main thread; decoding runs on a private thread.
 */
class CodecLoopPlayer(
    private val context: Context,
    private val remote: RemoteFiles,
    private val spec: LoopSpec,
) : LoopPlayer {

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("codec-loop").apply { start() }
    private val worker = Handler(thread.looper)

    override var listener: LoopPlayer.Listener? = null
    override var repeat: Boolean = false

    @Volatile override var speed: Float = 1f
        set(value) {
            worker.post { reanchor() }
            field = value
        }

    @Volatile private var positionUs = 0L
    @Volatile private var ready = false
    @Volatile private var ended = false
    @Volatile private var playing = false
    @Volatile private var released = false

    override val positionMs: Long get() = positionUs / 1000
    override val isReady: Boolean get() = ready
    override val isEnded: Boolean get() = ended

    // Decoder thread state.
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var format: MediaFormat? = null
    private var surface: Surface? = null
    private var maxLayer = Int.MAX_VALUE
    private var inputDone = false
    private var pendingInput = -1
    private var heldOutput = -1
    private val heldInfo = MediaCodec.BufferInfo()
    private var anchorNanos = 0L
    private var anchorUs = 0L
    private var firstFramePending = true

    private val startUs = spec.clipStartMs * 1000
    private val endUs = spec.clipEndMs?.let { it * 1000 } ?: Long.MAX_VALUE

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            try {
                step()
            } catch (e: Exception) {
                fail(e)
                return
            }
            worker.postDelayed(this, if (playing || firstFramePending) TICK_MS else IDLE_TICK_MS)
        }
    }

    init {
        worker.post {
            try {
                open()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    override fun setSurface(surface: Surface?) {
        worker.post {
            this.surface = surface
            val active = codec
            when {
                surface == null -> Unit // keep decoding, frames are dropped until a surface returns
                active == null -> runCatching { startCodec(surface) }.onFailure { fail(it) }
                else -> runCatching { active.setOutputSurface(surface) }
            }
        }
    }

    override fun play() {
        worker.post {
            if (playing) return@post
            playing = true
            reanchor()
            worker.removeCallbacks(tick)
            worker.post(tick)
        }
    }

    override fun pause() {
        worker.post { playing = false }
    }

    override fun seekToStart(positionMs: Long) {
        worker.post {
            if (released) return@post
            // After a failure everything is rebuilt: a new connection and a fresh decoder.
            val ex = extractor ?: run {
                try {
                    open()
                } catch (e: Exception) {
                    fail(e)
                }
                return@post
            }
            ex.seekTo(startUs + positionMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec?.let {
                heldOutput = -1
                pendingInput = -1
                it.flush()
            }
            inputDone = false
            ended = false
            positionUs = positionMs * 1000
            firstFramePending = true
            reanchor()
            worker.removeCallbacks(tick)
            worker.post(tick)
        }
    }

    override fun release() {
        released = true
        worker.post {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            codec = null
            extractor = null
            thread.quitSafely()
        }
    }

    // region decoder thread

    private fun open() {
        val ex = MediaExtractor()
        val uri = spec.uri
        if (uri.scheme == "http" || uri.scheme == "https") {
            ex.setDataSource(remote.open(uri.toString()))
        } else {
            ex.setDataSource(context, uri, null)
        }
        val track = (0 until ex.trackCount).first {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        ex.selectTrack(track)
        val trackFormat = ex.getTrackFormat(track)
        maxLayer = HevcTemporalLayers.scanMaxLayer(ex, trackFormat, startUs, spec.maxFps)
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        extractor = ex
        format = trackFormat
        val width = trackFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = trackFormat.getInteger(MediaFormat.KEY_HEIGHT)
        main.post { listener?.onVideoSize(width, height, 1f) }
        surface?.let { startCodec(it) }
        worker.post(tick)
    }

    private fun startCodec(target: Surface) {
        val trackFormat = format ?: return
        val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(trackFormat, target, null, 0)
        decoder.start()
        codec = decoder
    }

    private fun step() {
        val decoder = codec ?: return
        if (playing || firstFramePending) feed(decoder)
        drain(decoder)
    }

    /** Queues samples of the kept temporal layers until the window end. */
    private fun feed(decoder: MediaCodec) {
        val ex = extractor ?: return
        repeat(MAX_FEED_PER_TICK) {
            if (inputDone) return
            if (pendingInput < 0) pendingInput = decoder.dequeueInputBuffer(0)
            if (pendingInput < 0) return
            val buffer = decoder.getInputBuffer(pendingInput) ?: return
            val size = ex.readSampleData(buffer, 0)
            val timeUs = ex.sampleTime
            if (size < 0 || timeUs > endUs) {
                decoder.queueInputBuffer(pendingInput, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                pendingInput = -1
                inputDone = true
                return
            }
            buffer.position(0)
            val layer = if (maxLayer == Int.MAX_VALUE) 0 else HevcTemporalLayers.temporalId(buffer, size) ?: 0
            if (layer <= maxLayer) {
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                decoder.queueInputBuffer(pendingInput, 0, size, timeUs, flags)
                pendingInput = -1
            }
            ex.advance()
        }
    }

    /** Renders decoded pictures on time (or drops late/pre-window ones). */
    private fun drain(decoder: MediaCodec) {
        while (true) {
            val index: Int
            val info: MediaCodec.BufferInfo
            if (heldOutput >= 0) {
                index = heldOutput
                info = heldInfo
            } else {
                info = MediaCodec.BufferInfo()
                index = decoder.dequeueOutputBuffer(info, 0)
                if (index < 0) return
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                decoder.releaseOutputBuffer(index, false)
                heldOutput = -1
                onWindowEnd()
                return
            }
            val relativeUs = info.presentationTimeUs - startUs
            if (relativeUs < 0 || surface == null) {
                decoder.releaseOutputBuffer(index, false)
                heldOutput = -1
                continue
            }
            if (firstFramePending) {
                decoder.releaseOutputBuffer(index, true)
                heldOutput = -1
                firstFramePending = false
                positionUs = relativeUs
                reanchor()
                if (!ready) {
                    ready = true
                    main.post { listener?.onFirstFrame() }
                }
                continue
            }
            if (!playing) {
                hold(index, info)
                return
            }
            val now = System.nanoTime()
            val due = anchorNanos + ((relativeUs - anchorUs) * 1000 / speed).toLong()
            when {
                due - now > EARLY_NANOS -> {
                    hold(index, info)
                    return
                }
                now - due > LATE_NANOS -> decoder.releaseOutputBuffer(index, false)
                else -> decoder.releaseOutputBuffer(index, due)
            }
            heldOutput = -1
            positionUs = relativeUs
        }
    }

    private fun hold(index: Int, info: MediaCodec.BufferInfo) {
        if (heldOutput < 0) {
            heldOutput = index
            heldInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags)
        }
    }

    private fun onWindowEnd() {
        if (repeat) {
            val ex = extractor ?: return
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec?.flush()
            pendingInput = -1
            inputDone = false
            positionUs = 0
            firstFramePending = true
        } else {
            ended = true
            playing = false
        }
    }

    private fun reanchor() {
        anchorNanos = System.nanoTime()
        anchorUs = positionUs
    }

    private fun fail(error: Throwable) {
        playing = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
        format = null
        inputDone = false
        pendingInput = -1
        heldOutput = -1
        ended = false
        firstFramePending = true
        val exception = error as? Exception ?: RuntimeException(error)
        main.post { listener?.onError(exception) }
    }

    // endregion

    private companion object {
        const val TICK_MS = 4L
        const val IDLE_TICK_MS = 30L
        const val MAX_FEED_PER_TICK = 8
        const val EARLY_NANOS = 40_000_000L
        const val LATE_NANOS = 60_000_000L
    }
}
