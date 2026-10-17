package com.wallper.live.media

import android.media.MediaDataSource
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Random access to remote videos for MediaExtractor/MediaMetadataRetriever.
 *
 * Files are read in 1 MiB chunks via HTTP range requests, kept in a shared disk cache
 * and fetched ahead of the read position in the background. So playback never waits
 * on the network chunk by chunk, two players on the same video download it once, and
 * "Install" reuses what the preview already downloaded.
 */
class RemoteFiles(
    private val http: OkHttpClient,
    private val directory: File,
) {
    private val prefetcher = Executors.newFixedThreadPool(PREFETCH_THREADS) { r ->
        Thread(r, "range-prefetch").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val inFlight = ConcurrentHashMap<String, Future<ByteArray>>()
    private val lengths = ConcurrentHashMap<String, Long>()

    init {
        prefetcher.execute { trim() }
    }

    fun open(url: String): MediaDataSource = Source(url)

    private inner class Source(private val url: String) : MediaDataSource() {
        private val key = sha1(url)
        private val memory = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MEMORY_CHUNKS
        }
        private val length: Long by lazy { lengths.getOrPut(url) { fetchLength(url) } }

        override fun getSize(): Long = length

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            var pos = position
            var out = offset
            var remaining = minOf(size.toLong(), length - position).toInt()
            var total = 0
            while (remaining > 0) {
                val index = pos / CHUNK
                val chunk = chunk(index)
                val inChunk = (pos - index * CHUNK).toInt()
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
            synchronized(memory) { memory.clear() }
        }

        private fun chunk(index: Long): ByteArray {
            synchronized(memory) { memory[index]?.let { return it } }
            val data = load(index)
            synchronized(memory) { memory[index] = data }
            val lastChunk = (length - 1) / CHUNK
            for (ahead in 1..READ_AHEAD) {
                val next = index + ahead
                if (next > lastChunk) break
                prefetch(next)
            }
            return data
        }

        private fun load(index: Long): ByteArray {
            cachedFile(index).takeIf { it.exists() }?.let { file ->
                runCatching { return file.readBytes().also { file.setLastModified(System.currentTimeMillis()) } }
            }
            return prefetch(index).get(60, TimeUnit.SECONDS)
        }

        private fun prefetch(index: Long): Future<ByteArray> {
            val id = "$key-$index"
            inFlight[id]?.let { return it }
            val file = cachedFile(index)
            if (file.exists()) return CompletableFuture.completedFuture(file.readBytes())
            val task = FutureTask<ByteArray>(Callable {
                try {
                    val start = index * CHUNK
                    val end = minOf(length, start + CHUNK) - 1
                    val bytes = fetchRange(url, start, end)
                    directory.mkdirs()
                    val tmp = File(directory, "$id.tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(file)
                    bytes
                } finally {
                    inFlight.remove(id)
                }
            })
            val existing = inFlight.putIfAbsent(id, task)
            if (existing != null) return existing
            prefetcher.execute(task)
            return task
        }

        private fun cachedFile(index: Long) = File(directory, "$key-$index")
    }

    private fun fetchLength(url: String): Long = retrying {
        http.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { response ->
            response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
                ?: throw IOException("Unknown length (HTTP ${response.code})")
        }
    }

    private fun fetchRange(url: String, start: Long, end: Long): ByteArray = retrying {
        http.newCall(Request.Builder().url(url).header("Range", "bytes=$start-$end").build()).execute().use { response ->
            if (response.code != 206) throw IOException("Range not supported (HTTP ${response.code})")
            val bytes = response.body?.bytes() ?: throw IOException("Empty body")
            if (bytes.size.toLong() != end - start + 1) throw IOException("Short read")
            bytes
        }
    }

    private fun <T> retrying(block: () -> T): T {
        var last: IOException? = null
        for (attempt in 0 until ATTEMPTS) {
            try {
                return block()
            } catch (e: IOException) {
                last = e
                // Covers a network or VPN switch: 0.5, 1, 2, 4, 4 s.
                if (attempt < ATTEMPTS - 1) Thread.sleep(minOf(500L shl attempt, 4_000L))
            }
        }
        throw last ?: IOException("Request failed")
    }

    /** Keeps the cache under its budget, dropping the least recently used chunks. */
    private fun trim() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            total -= file.length()
            file.delete()
            if (total <= MAX_CACHE_BYTES * 3 / 4) break
        }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CHUNK = 1L shl 20 // 1 MiB
        const val READ_AHEAD = 8 // ≈ 5 s of 4K Aerials
        const val MEMORY_CHUNKS = 12
        const val PREFETCH_THREADS = 3
        const val ATTEMPTS = 6
        const val MAX_CACHE_BYTES = 800L shl 20
    }
}
