package com.wallper.live.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.wallper.live.core.Footage
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Covers for catalog footage, cut on the device from the preview stream with range
 * requests (a single key frame plus the index, a few hundred KB) and cached on disk.
 */
class CoverRepository(
    private val http: OkHttpClient,
    private val directory: File,
) {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    private val permits = Semaphore(PARALLEL)

    fun cached(footage: Footage): File? = fileOf(footage).takeIf { it.exists() && it.length() > 0 }

    suspend fun cover(footage: Footage): File? {
        cached(footage)?.let { return it }
        val deferred = CompletableDeferred<File?>()
        val existing = inFlight.putIfAbsent(footage.id, deferred)
        if (existing != null) return existing.await()
        val result = try {
            permits.withPermit { render(footage) }
        } catch (_: Exception) {
            null
        }
        deferred.complete(result)
        inFlight.remove(footage.id)
        return result
    }

    private suspend fun render(footage: Footage): File? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(HttpMediaDataSource(http, footage.previewUrl))
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 60_000L
            val frame = retriever.getScaledFrameAtTime(
                durationMs * 300, // 30% in: past the opening fade
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                MAX_SIDE,
                MAX_SIDE,
            ) ?: return@withContext null
            val target = fileOf(footage)
            directory.mkdirs()
            val tmp = File(directory, "${footage.id}.tmp")
            FileOutputStream(tmp).use { frame.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            frame.recycle()
            tmp.renameTo(target)
            target
        } finally {
            retriever.release()
        }
    }

    private fun fileOf(footage: Footage) = File(directory, "${footage.id}.jpg")

    private companion object {
        const val PARALLEL = 3
        const val MAX_SIDE = 960
    }
}
