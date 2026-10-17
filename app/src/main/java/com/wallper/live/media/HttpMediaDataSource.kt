package com.wallper.live.media

import android.media.MediaDataSource
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Random-access view of a remote file built on HTTP range requests.
 *
 * Lets MediaExtractor/MediaMetadataRetriever read just the parts they need (the index
 * and the requested time range) instead of downloading a multi-hundred-megabyte master.
 */
class HttpMediaDataSource(
    private val http: OkHttpClient,
    private val url: String,
    private val onBytesFetched: (Long) -> Unit = {},
) : MediaDataSource() {

    private val chunks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_CHUNKS
    }

    private val length: Long by lazy(::fetchLength)

    override fun getSize(): Long = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        var pos = position
        var out = offset
        var remaining = minOf(size.toLong(), length - position).toInt()
        var total = 0
        while (remaining > 0) {
            val index = pos / CHUNK_SIZE
            val chunk = chunk(index)
            val inChunk = (pos - index * CHUNK_SIZE).toInt()
            val n = minOf(remaining, chunk.size - inChunk)
            if (n <= 0) break
            System.arraycopy(chunk, inChunk, buffer, out, n)
            pos += n
            out += n
            remaining -= n
            total += n
        }
        return if (total == 0) -1 else total
    }

    override fun close() {
        synchronized(chunks) { chunks.clear() }
    }

    private fun chunk(index: Long): ByteArray {
        synchronized(chunks) { chunks[index]?.let { return it } }
        val data = fetchRange(index * CHUNK_SIZE, minOf(length, (index + 1) * CHUNK_SIZE) - 1)
        synchronized(chunks) { chunks[index] = data }
        onBytesFetched(data.size.toLong())
        return data
    }

    private fun fetchLength(): Long {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        return retrying {
            http.newCall(request).execute().use { response ->
                response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
                    ?: throw IOException("Unknown length (HTTP ${response.code})")
            }
        }
    }

    private fun fetchRange(start: Long, end: Long): ByteArray {
        val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        return retrying {
            http.newCall(request).execute().use { response ->
                if (response.code != 206) throw IOException("Range not supported (HTTP ${response.code})")
                val bytes = response.body?.bytes() ?: throw IOException("Empty body")
                if (bytes.size.toLong() != end - start + 1) throw IOException("Short read")
                bytes
            }
        }
    }

    private fun <T> retrying(block: () -> T): T {
        var last: IOException? = null
        for (attempt in 0 until ATTEMPTS) {
            try {
                return block()
            } catch (e: IOException) {
                last = e
                Thread.sleep(500L * (attempt + 1))
            }
        }
        throw last ?: IOException("Request failed")
    }

    private companion object {
        const val CHUNK_SIZE = 1L shl 20 // 1 MiB
        const val MAX_CHUNKS = 24
        const val ATTEMPTS = 4
    }
}
