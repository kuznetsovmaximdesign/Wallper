package com.wallper.live.data.catalog

import com.wallper.live.core.Footage
import com.wallper.live.core.FootageCategory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Apple's public Aerials catalog (the tvOS screen-saver manifest), fetched on the
 * device and cached. Videos are streamed from Apple's CDN by the user's own device.
 */
class AppleAerials(
    private val http: OkHttpClient,
    private val json: Json,
    cacheDir: File,
) {
    private val cacheFile = File(cacheDir, "apple-aerials-entries.json")

    suspend fun load(forceRefresh: Boolean): List<Footage> = withContext(Dispatchers.IO) {
        val cached = cacheFile.takeIf { it.exists() }
        val fresh = cached != null && System.currentTimeMillis() - cached.lastModified() < MAX_AGE_MS
        val bytes = if (fresh && !forceRefresh) {
            cached!!.readBytes()
        } else {
            try {
                download().also { cacheFile.writeBytes(it) }
            } catch (e: IOException) {
                cached?.readBytes() ?: throw e
            }
        }
        parse(bytes)
    }

    private fun download(): ByteArray {
        val request = Request.Builder().url(MANIFEST_URL).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Manifest HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty manifest")
            return body.byteStream().use { TarReader.extract(it, "entries.json") }
                ?: throw IOException("entries.json not found in manifest")
        }
    }

    internal fun parse(bytes: ByteArray): List<Footage> {
        val entries = json.decodeFromString(Entries.serializer(), bytes.decodeToString())
        val categories = entries.categories.associate { it.id to categoryOf(it.localizedNameKey) }
        return entries.assets
            .mapNotNull { asset ->
                val master = asset.url4kSdr ?: return@mapNotNull null
                Footage(
                    id = asset.id,
                    title = AppleTitles.russian(asset.accessibilityLabel),
                    category = asset.categories.firstNotNullOfOrNull { categories[it] } ?: FootageCategory.Landscapes,
                    masterUrl = master,
                    previewUrl = asset.url1080H264 ?: asset.url1080Sdr ?: master,
                )
            }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.category.ordinal }, { it.title }))
    }

    private fun categoryOf(key: String): FootageCategory = when {
        key.contains("Underwater", ignoreCase = true) -> FootageCategory.Underwater
        key.contains("Space", ignoreCase = true) -> FootageCategory.Space
        key.contains("Cities", ignoreCase = true) -> FootageCategory.Cities
        else -> FootageCategory.Landscapes
    }

    @Serializable
    internal data class Entries(
        val assets: List<Asset> = emptyList(),
        val categories: List<Category> = emptyList(),
    )

    @Serializable
    internal data class Asset(
        val id: String,
        val accessibilityLabel: String = "",
        val categories: List<String> = emptyList(),
        @SerialName("url-4K-SDR") val url4kSdr: String? = null,
        @SerialName("url-1080-SDR") val url1080Sdr: String? = null,
        @SerialName("url-1080-H264") val url1080H264: String? = null,
    )

    @Serializable
    internal data class Category(
        val id: String,
        val localizedNameKey: String = "",
    )

    companion object {
        const val MANIFEST_URL = "https://sylvan.apple.com/Aerials/resources-16.tar"
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)
    }
}
