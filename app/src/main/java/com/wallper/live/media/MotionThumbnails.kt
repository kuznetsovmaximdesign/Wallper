package com.wallper.live.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * "Live thumbnails": a seamless ~5.5 s, 576×1024 H.264 loop cut from each video and
 * cached, plus a sharp poster frame.
 *
 * Cards can then play real motion several at a time, which full 4K streams cannot.
 * The pipeline decodes the source straight into an OpenGL texture (dropping upper
 * HEVC temporal layers of 240 fps masters first), centre-crops it to portrait and
 * feeds a hardware H.264 encoder through its input surface. The first half second is
 * kept on the GPU and cross-faded into the last one, so the loop has no visible seam.
 */
class MotionThumbnails(
    private val context: Context,
    private val remote: RemoteFiles,
    private val directory: File,
) {
    private val dispatcher = Executors.newFixedThreadPool(PARALLEL) { Thread(it, "motion-thumbs") }.asCoroutineDispatcher()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File?>>()

    fun cached(id: String): File? = fileOf(id).takeIf { it.exists() && it.length() > 0 }

    /** Sharp first frame of the loop, written together with it. */
    fun poster(id: String): File? = posterOf(id).takeIf { it.exists() && it.length() > 0 && cached(id) != null }

    /** Returns the cached loop for [id], generating it from [uri] if needed. */
    suspend fun get(id: String, uri: String): File? {
        cached(id)?.let { return it }
        val deferred = CompletableDeferred<File?>()
        val existing = inFlight.putIfAbsent(id, deferred)
        if (existing != null) return existing.await()
        try {
            val result = withContext(dispatcher) {
                runCatching { generate(uri, fileOf(id), posterOf(id)) }.getOrNull()
            }
            deferred.complete(result)
            return result
        } finally {
            if (!deferred.isCompleted) deferred.complete(null)
            inFlight.remove(id)
        }
    }

    private fun fileOf(id: String) = File(directory, "$id.mp4")
    private fun posterOf(id: String) = File(directory, "$id.jpg")

    private fun generate(uri: String, out: File, poster: File): File {
        directory.mkdirs()
        val tmp = File(directory, "${out.name}.tmp")
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: RecordableEgl? = null
        var surfaceTexture: SurfaceTexture? = null
        var program: CropProgram? = null
        var decoderSurface: Surface? = null
        val callbacks = HandlerThread("motion-frames").apply { start() }
        try {
            if (uri.startsWith("http")) extractor.setDataSource(remote.open(uri)) else extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val startUs = if (durationUs > LENGTH_US * 3) (durationUs * 0.3).toLong() else 0L
            val endUs = startUs + LENGTH_US
            val maxLayer = HevcTemporalLayers.scanMaxLayer(extractor, format, startUs, FPS.toDouble())
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            var srcW = format.getInteger(MediaFormat.KEY_WIDTH).toFloat()
            var srcH = format.getInteger(MediaFormat.KEY_HEIGHT).toFloat()
            if (rotation % 180 != 0) srcW = srcH.also { srcH = srcW }

            val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { encoder = it }
            enc.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val gl = RecordableEgl(enc.createInputSurface()).also { egl = it }
            enc.start()

            val crop = CropProgram(srcAspect = srcW / srcH, dstAspect = WIDTH.toFloat() / HEIGHT, WIDTH, HEIGHT)
                .also { program = it }
            val texture = crop.createTexture()
            val frameLock = Object()
            var frameReady = false
            val st = SurfaceTexture(texture).also { surfaceTexture = it }
            st.setOnFrameAvailableListener({
                synchronized(frameLock) {
                    frameReady = true
                    frameLock.notifyAll()
                }
            }, Handler(callbacks.looper))
            val surface = Surface(st).also { decoderSurface = it }
            val dec = MediaCodec.createDecoderByType(mime).also { decoder = it }
            dec.configure(format, surface, null, 0)
            dec.start()

            val mux = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            var muxTrack = -1
            val matrix = FloatArray(16)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var frames = 0
            val deadline = System.nanoTime() + TIMEOUT_NANOS

            while (!encoderDone) {
                check(System.nanoTime() < deadline) { "Motion thumbnail timed out" }
                if (!inputDone) {
                    val index = dec.dequeueInputBuffer(TICK_US)
                    if (index >= 0) {
                        var queued = false
                        while (!queued) {
                            val buffer = dec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            val time = extractor.sampleTime
                            if (size < 0 || time > endUs) {
                                dec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                                queued = true
                            } else {
                                buffer.position(0)
                                val layer = if (maxLayer == Int.MAX_VALUE) 0 else HevcTemporalLayers.temporalId(buffer, size) ?: 0
                                if (layer <= maxLayer) {
                                    dec.queueInputBuffer(index, 0, size, time, 0)
                                    queued = true
                                }
                                extractor.advance()
                            }
                        }
                    }
                }
                if (!decoderDone) {
                    val index = dec.dequeueOutputBuffer(info, TICK_US)
                    if (index >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            dec.releaseOutputBuffer(index, false)
                            enc.signalEndOfInputStream()
                            decoderDone = true
                        } else {
                            val time = info.presentationTimeUs
                            val render = time >= startUs && time <= endUs
                            dec.releaseOutputBuffer(index, render)
                            if (render) {
                                synchronized(frameLock) {
                                    val waitUntil = System.currentTimeMillis() + FRAME_WAIT_MS
                                    while (!frameReady) {
                                        val left = waitUntil - System.currentTimeMillis()
                                        check(left > 0) { "Decoder frame never arrived" }
                                        frameLock.wait(left)
                                    }
                                    frameReady = false
                                }
                                st.updateTexImage()
                                st.getTransformMatrix(matrix)
                                val local = time - startUs
                                when {
                                    // The opening is kept to be blended into the ending.
                                    local < FADE_US -> crop.keepHead(texture, matrix, local.toFloat() / FADE_US)
                                    else -> {
                                        val tail = local - (LENGTH_US - FADE_US)
                                        val head = if (tail >= 0) (tail * crop.headCount / FADE_US).toInt() else -1
                                        crop.draw(texture, matrix)
                                        if (head >= 0) crop.blendHead(head, tail.toFloat() / FADE_US)
                                        if (frames == 0) runCatching { crop.savePoster(poster) }
                                        gl.swap((local - FADE_US) * 1000)
                                        frames++
                                    }
                                }
                            }
                        }
                    }
                }
                // Drain the encoder into the muxer.
                while (true) {
                    val index = enc.dequeueOutputBuffer(info, if (decoderDone) TICK_US else 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = mux.addTrack(enc.outputFormat)
                        mux.start()
                        continue
                    }
                    if (index < 0) break
                    val data = enc.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxTrack >= 0) mux.writeSampleData(muxTrack, data, info)
                    enc.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoderDone = true
                        break
                    }
                }
            }
            check(frames > 0) { "No frames" }
            mux.stop()
            muxer = null
            mux.release()
            tmp.renameTo(out)
            return out
        } catch (e: Exception) {
            poster.delete()
            throw e
        } finally {
            runCatching { muxer?.release() }
            runCatching { decoder?.stop(); decoder?.release() }
            runCatching { encoder?.stop(); encoder?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { program?.release() }
            runCatching { egl?.release() }
            runCatching { extractor.release() }
            callbacks.quitSafely()
            tmp.delete()
        }
    }

    private companion object {
        const val PARALLEL = 2
        const val WIDTH = 576
        const val HEIGHT = 1024
        const val FPS = 30
        const val BITRATE = 3_000_000
        /** Source window; the loop itself is LENGTH_US - FADE_US long. */
        const val LENGTH_US = 6_000_000L
        const val FADE_US = 500_000L
        const val TICK_US = 10_000L
        const val FRAME_WAIT_MS = 2_500L
        const val TIMEOUT_NANOS = 90_000_000_000L
    }
}

/** EGL context rendering into a MediaCodec encoder's input surface. */
private class RecordableEgl(private val surface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }
    }

    fun swap(presentationNanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationNanos)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        surface.release()
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/**
 * Draws the external (decoder) texture centre-cropped from [srcAspect] to the output,
 * keeps the loop's opening in textures and blends it back over the ending.
 */
private class CropProgram(srcAspect: Float, dstAspect: Float, private val width: Int, private val height: Int) {
    private val crop = link(VERTEX, CROP_FRAGMENT)
    private val copy = link(VERTEX, COPY_FRAGMENT)
    private val uMatrix = GLES30.glGetUniformLocation(crop, "uSt")
    private val uScale = GLES30.glGetUniformLocation(crop, "uScale")
    private val uOffset = GLES30.glGetUniformLocation(crop, "uOffset")
    private val scaleX: Float
    private val scaleY: Float
    private val heads = ArrayList<Int>()
    private var framebuffer = 0

    val headCount: Int get() = heads.size

    init {
        if (srcAspect > dstAspect) {
            scaleX = dstAspect / srcAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = srcAspect / dstAspect
        }
    }

    fun createTexture(): Int = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

    /** Draws the current frame to the output surface. */
    fun draw(texture: Int, matrix: FloatArray) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        drawCrop(texture, matrix)
    }

    /** Stores an opening frame at [progress] (0..1) through the fade, at most [MAX_HEADS] of them. */
    fun keepHead(texture: Int, matrix: FloatArray, progress: Float) {
        if (heads.size >= MAX_HEADS || progress * MAX_HEADS < heads.size) return
        val target = texture(GLES30.GL_TEXTURE_2D)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        if (framebuffer == 0) {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, target, 0)
        drawCrop(texture, matrix)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        heads += target
    }

    /** Blends stored opening frame [index] over what is drawn, with weight [amount]. */
    fun blendHead(index: Int, amount: Float) {
        if (heads.isEmpty()) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendColor(0f, 0f, 0f, amount.coerceIn(0f, 1f))
        GLES30.glBlendFunc(GLES30.GL_CONSTANT_ALPHA, GLES30.GL_ONE_MINUS_CONSTANT_ALPHA)
        GLES30.glUseProgram(copy)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, heads[index.coerceIn(0, heads.size - 1)])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Saves what is currently drawn as a JPEG. */
    fun savePoster(file: File) {
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(pixels)
        // GL rows start at the bottom.
        val upright = Bitmap.createBitmap(raw, 0, 0, width, height, Matrix().apply { preScale(1f, -1f) }, false)
        raw.recycle()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { upright.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        upright.recycle()
        tmp.renameTo(file)
    }

    fun release() {
        if (heads.isNotEmpty()) GLES30.glDeleteTextures(heads.size, heads.toIntArray(), 0)
        heads.clear()
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteProgram(crop)
        GLES30.glDeleteProgram(copy)
    }

    private fun drawCrop(texture: Int, matrix: FloatArray) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(crop)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES30.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0)
        GLES30.glUniform2f(uScale, scaleX, scaleY)
        GLES30.glUniform2f(uOffset, (1f - scaleX) / 2f, (1f - scaleY) / 2f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun texture(target: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(target, ids[0])
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES30.glGetShaderInfoLog(shader) }
            return shader
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        return p
    }

    private companion object {
        /** 30 fps over the half-second fade, ≈ 35 MB of GPU memory at 576×1024. */
        const val MAX_HEADS = 15

        const val VERTEX = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""
        const val CROP_FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform mat4 uSt;
uniform vec2 uScale;
uniform vec2 uOffset;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec2 uv = vUv * uScale + uOffset;
    fragColor = vec4(texture(uTex, (uSt * vec4(uv, 0.0, 1.0)).xy).rgb, 1.0);
}
"""
        const val COPY_FRAGMENT = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 fragColor;
void main() {
    fragColor = vec4(texture(uTex, vUv).rgb, 1.0);
}
"""
    }
}
