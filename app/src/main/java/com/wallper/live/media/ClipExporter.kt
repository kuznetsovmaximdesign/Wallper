package com.wallper.live.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

sealed interface ClipSource {
    data class Remote(val url: String) : ClipSource
    data class Local(val uri: Uri) : ClipSource
}

data class ExportedClip(
    val durationMs: Long,
    val width: Int,
    val height: Int,
)

/**
 * Cuts a time range out of a video without re-encoding: samples are copied from the
 * source container into a new MP4, so the wallpaper keeps the original 4K bitstream.
 * The cut starts on the key frame at or before the requested start and ends on a GOP
 * boundary after the requested end. Audio is dropped. High-frame-rate HEVC (Apple's
 * 240 fps macOS Aerials) is reduced to what the device can decode (60 or 30 fps) by
 * dropping upper temporal layers — still without re-encoding.
 */
class ClipExporter(
    private val context: Context,
    private val http: OkHttpClient,
) {

    suspend fun export(
        source: ClipSource,
        startMs: Long,
        lengthMs: Long,
        output: File,
        onProgress: (Float) -> Unit,
    ): ExportedClip = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            when (source) {
                is ClipSource.Remote -> extractor.setDataSource(HttpMediaDataSource(http, source.url))
                is ClipSource.Local -> extractor.setDataSource(context, source.uri, null)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("No video track")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)

            val rotation = format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val startUs = startMs * 1000
            val endUs = (startMs + lengthMs) * 1000

            val capacity = maxOf(format.intOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0, MIN_BUFFER)
            val buffer = ByteBuffer.allocateDirect(capacity)
            val maxLayer = HevcTemporalLayers.scanMaxLayer(extractor, format, startUs)

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            output.parentFile?.mkdirs()
            val mux = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            mux.setOrientationHint(rotation)
            val outTrack = mux.addTrack(format)
            mux.start()

            val info = MediaCodec.BufferInfo()
            var firstUs = -1L
            var lastUs = 0L
            var frameUs = 33_333L
            var previousUs = -1L

            while (true) {
                coroutineContext.ensureActive()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime
                val isSync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                if (firstUs < 0) firstUs = timeUs
                if (timeUs > endUs && isSync) break
                buffer.position(0)
                val dropped = maxLayer != Int.MAX_VALUE &&
                    (HevcTemporalLayers.temporalId(buffer, size) ?: 0) > maxLayer
                if (!dropped && timeUs >= firstUs) {
                    info.set(0, size, timeUs - firstUs, if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(outTrack, buffer, info)
                    if (previousUs >= 0 && timeUs > previousUs) frameUs = minOf(frameUs, timeUs - previousUs)
                    previousUs = timeUs
                    lastUs = maxOf(lastUs, timeUs - firstUs)
                }
                onProgress(((timeUs - firstUs).toFloat() / (endUs - firstUs).coerceAtLeast(1)).coerceIn(0f, 1f))
                extractor.advance()
            }
            check(firstUs >= 0) { "Empty range" }
            mux.stop()
            onProgress(1f)
            ExportedClip(durationMs = (lastUs + frameUs) / 1000, width = width, height = height)
        } catch (t: Throwable) {
            output.delete()
            throw t
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    /** Saves a frame of a local clip as a JPEG cover. */
    suspend fun writeCover(video: File, atMs: Long, cover: File): Boolean = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.path)
            val frame = retriever.getScaledFrameAtTime(
                atMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                COVER_MAX_SIDE,
                COVER_MAX_SIDE,
            ) ?: return@withContext false
            FileOutputStream(cover).use { frame.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            frame.recycle()
            true
        } catch (_: Exception) {
            false
        } finally {
            retriever.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private companion object {
        const val MIN_BUFFER = 12 shl 20 // 4K HEVC key frames can be several MB
        const val COVER_MAX_SIDE = 1280
    }
}
