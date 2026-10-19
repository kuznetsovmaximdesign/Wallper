package com.wallper.live.data.catalog

import com.wallper.live.core.Footage
import com.wallper.live.core.FootageCategory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Apple's public Aerials catalogs, fetched on the device and cached:
 *  - macOS 26 (156 scenes incl. Tahoe, Sequoia, Sonoma, India, Mac colours; 4K 240 fps),
 *  - tvOS 16 (older scenes as 4K 30 fps masters plus light 1080p streams).
 * Scenes present in both use the tvOS files (live preview, no frame-rate reduction).
 * Russian names come from Apple's own localisation shipped in each manifest.
 */
class AppleAerials(
    private val http: OkHttpClient,
    private val json: Json,
    private val cacheDir: File,
) : CatalogSource {

    override suspend fun load(forceRefresh: Boolean): Catalog = withContext(Dispatchers.IO) {
        coroutineScope {
            val mac = async { runCatching { feed("macos26", forceRefresh) { macResourcesUrl() } }.getOrNull() }
            val tv = async { runCatching { feed("tvos16", forceRefresh) { TV_MANIFEST } }.getOrNull() }
            val macFeed = mac.await()
            val tvFeed = tv.await()
            val items = merge(macFeed, tvFeed).ifEmpty { throw IOException("No Aerials catalog available") }
            Catalog(items, complete = macFeed != null && tvFeed != null)
        }
    }

    internal class Feed(val entries: Entries, val strings: Map<String, String>)

    private fun feed(name: String, forceRefresh: Boolean, url: () -> String): Feed {
        val cache = File(cacheDir, "aerials-$name.tar")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
        val tar = if (fresh && !forceRefresh) {
            cache.readBytes()
        } else {
            try {
                download(url()).also { cache.writeBytes(it) }
            } catch (e: IOException) {
                if (cache.exists()) cache.readBytes() else throw e
            }
        }
        return parseFeed(tar)
    }

    internal fun parseFeed(tar: ByteArray): Feed {
        val files = TarReader.extractAll(ByteArrayInputStream(tar)) {
            it == "entries.json" || it.endsWith("ru.lproj/Localizable.nocache.strings")
        }
        val entries = json.decodeFromString(Entries.serializer(), files.getValue("entries.json").decodeToString())
        val strings = files.entries.firstOrNull { it.key.endsWith(".strings") }
            ?.let { runCatching { Plists.parseStrings(it.value) }.getOrNull() }
            .orEmpty()
        return Feed(entries, strings)
    }

    internal fun merge(mac: Feed?, tv: Feed?): List<Footage> {
        val tvAssets = tv?.entries?.assets.orEmpty().filter { it.url4kSdr != null }
        val tvByKey = HashMap<String, Asset>()
        tvAssets.forEach { asset -> matchKeys(asset).forEach { tvByKey.putIfAbsent(it, asset) } }
        val used = HashSet<String>()
        val result = ArrayList<Footage>()

        mac?.let { feed ->
            val categories = feed.entries.categories.associate { it.id to categoryOf(it.localizedNameKey) }
            val subcategories = feed.entries.categories.flatMap { it.subcategories }.associate { it.id to it.localizedNameKey }
            for (asset in feed.entries.assets.sortedBy { it.preferredOrder ?: Int.MAX_VALUE }) {
                val master240 = asset.url4kSdr240 ?: asset.url4kSdr ?: continue
                val twin = matchKeys(asset).firstNotNullOfOrNull { tvByKey[it] }
                twin?.let { used += it.id }
                result += Footage(
                    id = asset.id,
                    title = feed.strings[asset.localizedNameKey] ?: AppleTitles.russian(asset.accessibilityLabel),
                    collection = asset.subcategories.firstNotNullOfOrNull { subcategories[it] }
                        ?.let { feed.strings[it] },
                    category = Topics.of(
                        asset.categories.firstNotNullOfOrNull { categories[it] } ?: FootageCategory.Landscapes,
                        asset.subcategories.firstNotNullOfOrNull { subcategories[it] },
                        asset.accessibilityLabel,
                    ),
                    night = Topics.isNight(asset.accessibilityLabel),
                    masterUrl = twin?.url4kSdr ?: master240,
                    previewUrl = twin?.let { it.url1080H264 ?: it.url1080Sdr },
                    coverUrl = asset.previewImage,
                    highFrameRate = twin == null && asset.url4kSdr240 != null,
                )
            }
        }

        tv?.let { feed ->
            val categories = feed.entries.categories.associate { it.id to categoryOf(it.localizedNameKey) }
            for (asset in tvAssets) {
                if (asset.id in used || result.any { it.id == asset.id }) continue
                result += Footage(
                    id = asset.id,
                    title = feed.strings[asset.localizedNameKey] ?: AppleTitles.russian(asset.accessibilityLabel),
                    collection = null,
                    category = Topics.of(
                        asset.categories.firstNotNullOfOrNull { categories[it] } ?: FootageCategory.Landscapes,
                        null,
                        asset.accessibilityLabel,
                    ),
                    night = Topics.isNight(asset.accessibilityLabel),
                    masterUrl = asset.url4kSdr!!,
                    previewUrl = asset.url1080H264 ?: asset.url1080Sdr,
                    coverUrl = asset.previewImage,
                    highFrameRate = false,
                )
            }
        }
        return interleave(result.distinctBy { it.id })
    }

    /**
     * "All" mixes the categories (a landscape, an underwater scene, a city, …) instead of
     * listing dozens of landscapes first; within a category Apple's own order is kept.
     */
    internal fun interleave(items: List<Footage>): List<Footage> {
        val queues = items.groupBy { it.category }.toSortedMap(compareBy { it.ordinal }).values.map { ArrayDeque(it) }
        val mixed = ArrayList<Footage>(items.size)
        while (mixed.size < items.size) {
            for (queue in queues) queue.removeFirstOrNull()?.let(mixed::add)
        }
        return mixed
    }

    /** Keys linking the same shot across feeds: shot id and the master's file stem. */
    private fun matchKeys(asset: Asset): List<String> = listOfNotNull(
        asset.shotID?.lowercase()?.let { "shot:$it" },
        (asset.url4kSdr ?: asset.url4kSdr240)?.substringAfterLast('/')?.lowercase()
            ?.substringBefore("_sdr")?.let { "file:$it" },
    )

    private fun macResourcesUrl(): String {
        val plist = download(MAC_CONFIG)
        return Plists.stringValue(plist, "resources-url") ?: throw IOException("No resources-url in config")
    }

    private fun download(url: String): ByteArray {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.bytes() ?: throw IOException("Empty body for $url")
        }
    }

    private fun categoryOf(key: String): FootageCategory = when {
        key.contains("Underwater", ignoreCase = true) -> FootageCategory.Underwater
        key.contains("Space", ignoreCase = true) -> FootageCategory.Space
        key.contains("Cities", ignoreCase = true) -> FootageCategory.Cities
        key.contains("Mac", ignoreCase = true) -> FootageCategory.Abstract
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
        val localizedNameKey: String = "",
        val shotID: String? = null,
        val categories: List<String> = emptyList(),
        val subcategories: List<String> = emptyList(),
        val preferredOrder: Int? = null,
        val previewImage: String? = null,
        @SerialName("url-4K-SDR") val url4kSdr: String? = null,
        @SerialName("url-4K-SDR-240FPS") val url4kSdr240: String? = null,
        @SerialName("url-1080-SDR") val url1080Sdr: String? = null,
        @SerialName("url-1080-H264") val url1080H264: String? = null,
    )

    @Serializable
    internal data class Category(
        val id: String,
        val localizedNameKey: String = "",
        val subcategories: List<Subcategory> = emptyList(),
    )

    @Serializable
    internal data class Subcategory(
        val id: String,
        val localizedNameKey: String = "",
    )

    companion object {
        const val MAC_CONFIG =
            "https://configuration.apple.com/configurations/internetservices/aerials/resources-config-26-0.plist"
        const val TV_MANIFEST = "https://sylvan.apple.com/Aerials/resources-16.tar"
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)
    }
}
