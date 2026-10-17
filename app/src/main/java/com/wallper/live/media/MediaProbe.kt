package com.wallper.live.media

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Reads container metadata only (for remote files: a range request for the index). */
class MediaProbe(
    private val context: Context,
    private val http: OkHttpClient,
) {
    suspend fun durationMs(source: ClipSource): Long? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            when (source) {
                is ClipSource.Remote -> retriever.setDataSource(HttpMediaDataSource(http, source.url))
                is ClipSource.Local -> retriever.setDataSource(context, source.uri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
