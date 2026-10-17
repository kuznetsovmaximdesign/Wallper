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

/**
 * Sharp covers for catalog footage, cut on the device from the video itself (one key
 * frame plus the index via range requests) at up to 2560 px and cached on disk.
 * Apple's published stills are small, so they only serve as instant placeholders.
 */
class CoverRepository(
    private val remote: RemoteFiles,
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
        val result = permits.withPermit {
            // The 4K master gives the sharpest frame; the 1080p stream is the fallback.
            listOfNotNull(footage.masterUrl, footage.previewUrl).distinct().firstNotNullOfOrNull { url ->
                runCatching { render(footage, url) }.getOrNull()
            }
        }
        deferred.complete(result)
        inFlight.remove(footage.id)
        return result
    }

    private suspend fun render(footage: Footage, url: String): File? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(remote.open(url))
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
            FileOutputStream(tmp).use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            frame.recycle()
            tmp.renameTo(target)
            target
        } finally {
            retriever.release()
        }
    }

    private fun fileOf(footage: Footage) = File(directory, "v2-${footage.id}.jpg")

    private companion object {
        const val PARALLEL = 2
        const val MAX_SIDE = 2560
    }
}
