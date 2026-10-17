package com.wallper.live.media

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads container metadata only (for remote files: a range request for the index).
 * Durations are remembered, so a viewer opened a second time starts playing at once.
 */
class MediaProbe(
    private val context: Context,
    private val remote: RemoteFiles,
) {
    private val known by lazy { context.getSharedPreferences("probe-durations", Context.MODE_PRIVATE) }

    suspend fun durationMs(source: ClipSource): Long? = withContext(Dispatchers.IO) {
        val key = when (source) {
            is ClipSource.Remote -> source.url
            is ClipSource.Local -> source.uri.toString()
        }
        known.getLong(key, 0L).takeIf { it > 0 }?.let { return@withContext it }
        read(source)?.also { known.edit().putLong(key, it).apply() }
    }

    private fun read(source: ClipSource): Long? {
        val retriever = MediaMetadataRetriever()
        try {
            when (source) {
                is ClipSource.Remote -> retriever.setDataSource(remote.open(source.url))
                is ClipSource.Local -> retriever.setDataSource(context, source.uri)
            }
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            return null
        } finally {
            retriever.release()
        }
    }
}
