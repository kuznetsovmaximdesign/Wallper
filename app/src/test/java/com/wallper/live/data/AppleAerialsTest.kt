package com.wallper.live.data

import com.wallper.live.core.FootageCategory
import com.wallper.live.data.catalog.AppleAerials
import com.wallper.live.data.catalog.TarReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleAerialsTest {

    private val aerials = AppleAerials(OkHttpClient(), Json { ignoreUnknownKeys = true }, File("build/tmp"))

    // Shape of tvOS resources-16 entries.json (trimmed).
    private val tvEntries = """
        {
          "categories": [{"id": "U", "localizedNameKey": "AerialCategoryUnderwater"},
                         {"id": "S", "localizedNameKey": "AerialCategorySpace"}],
          "assets": [
            {"id": "TV-SEALS", "accessibilityLabel": "Seals", "categories": ["U"], "shotID": "SE_A016_C009",
             "url-4K-SDR": "https://sylvan.apple.com/Aerials/2x/Videos/SE_A016_C009_SDR_20190717_SDR_4K_HEVC.mov",
             "url-1080-H264": "https://sylvan.apple.com/Videos/SE_A016_C009_SDR_20190717_SDR_2K_AVC.mov"},
            {"id": "TV-AFRICA", "accessibilityLabel": "Africa Night", "categories": ["S"], "shotID": "X1",
             "url-4K-SDR": "https://sylvan.apple.com/Aerials/2x/Videos/comp_GMT312_AFRICA_NIGHT_v14_SDR_FINAL_20180706_SDR_4K_HEVC.mov",
             "url-1080-H264": "https://sylvan.apple.com/Videos/comp_GMT312_AFRICA_NIGHT_2K_AVC.mov"},
            {"id": "TV-ONLY", "accessibilityLabel": "Iceland", "categories": [], "shotID": "I003",
             "url-4K-SDR": "https://sylvan.apple.com/Aerials/2x/Videos/I003_C008_SDR_4K_HEVC.mov"},
            {"id": "NO-VIDEO", "accessibilityLabel": "Nothing"},
            {"id": "HD-ONLY", "accessibilityLabel": "London", "categories": [], "shotID": "L004",
             "url-1080-H264": "https://sylvan.apple.com/Videos/L004_2K_AVC.mov",
             "url-1080-SDR": "https://sylvan.apple.com/Videos/L004_1080_SDR.mov"}
          ]
        }
    """.trimIndent()

    // Shape of macOS 26 resources-26-x.tar entries.json (trimmed).
    private val macEntries = """
        {
          "categories": [
            {"id": "L", "localizedNameKey": "AerialCategoryLandscapes",
             "subcategories": [{"id": "SUB-TAHOE", "localizedNameKey": "AerialSubcategoryTahoe"}]},
            {"id": "U", "localizedNameKey": "AerialCategoryUnderwater"},
            {"id": "S", "localizedNameKey": "AerialCategorySpace"},
            {"id": "M", "localizedNameKey": "AerialCategoryMac"}
          ],
          "assets": [
            {"id": "MAC-TAHOE", "accessibilityLabel": "Tahoe Day", "localizedNameKey": "TAHOE_DAY_NAME",
             "categories": ["L"], "subcategories": ["SUB-TAHOE"], "shotID": "TH_DAY", "preferredOrder": 2,
             "previewImage": "https://sylvan.apple.com/tahoe.jpg",
             "url-4K-SDR-240FPS": "https://sylvan.apple.com/itunes-assets/tahoe_sdr_4k_240p_tsa.mov"},
            {"id": "MAC-SEALS", "accessibilityLabel": "Seals", "localizedNameKey": "SEALS_NAME",
             "categories": ["U"], "shotID": "SE_A016_C009", "preferredOrder": 1,
             "url-4K-SDR-240FPS": "https://sylvan.apple.com/itunes-assets/SE_A016_C009_sdr_4k_240p_tsa.mov"},
            {"id": "MAC-AFRICA", "accessibilityLabel": "Africa Night", "localizedNameKey": "AFRICA_NAME",
             "categories": ["S"], "shotID": "GMT312",
             "url-4K-SDR-240FPS": "https://sylvan.apple.com/itunes-assets/comp_GMT312_AFRICA_NIGHT_v14_SDR_FINAL_20180706_FRC240fps_sdr_4k_tsa.mov"},
            {"id": "MAC-PURPLE", "accessibilityLabel": "Mac Purple", "localizedNameKey": "MAC_WP_PPL_NAME",
             "categories": ["M"], "url-4K-SDR-240FPS": "https://sylvan.apple.com/itunes-assets/Silver_Comp_tsa.mov"}
          ]
        }
    """.trimIndent()

    private val ruStrings = "\"TAHOE_DAY_NAME\" = \"Тахо днём\";\n\"AerialSubcategoryTahoe\" = \"Тахо\";\n" +
        "\"SEALS_NAME\" = \"Тюлени\";\n"

    private fun macFeed() = aerials.parseFeed(
        tarOf(
            "./entries.json" to macEntries.encodeToByteArray(),
            "./TVIdleScreenStrings.bundle/Contents/Resources/ru.lproj/._Localizable.nocache.strings" to ByteArray(8),
            "./TVIdleScreenStrings.bundle/Contents/Resources/ru.lproj/Localizable.nocache.strings" to
                ruStrings.toByteArray(Charsets.UTF_16),
        ),
    )

    private fun tvFeed() = aerials.parseFeed(tarOf("entries.json" to tvEntries.encodeToByteArray()))

    @Test
    fun macScenesUseAppleRussianNamesAndCollections() {
        val items = aerials.merge(macFeed(), tvFeed())
        val tahoe = items.first { it.id == "MAC-TAHOE" }
        assertEquals("Тахо днём", tahoe.title)
        assertEquals("Тахо", tahoe.collection)
        assertEquals("https://sylvan.apple.com/tahoe.jpg", tahoe.coverUrl)
        assertTrue(tahoe.highFrameRate)
        assertNull(tahoe.previewUrl)
        assertEquals(FootageCategory.Mac, items.first { it.id == "MAC-PURPLE" }.category)
    }

    @Test
    fun scenesInBothFeedsUseTheTvosFilesForPreviewAndMaster() {
        val items = aerials.merge(macFeed(), tvFeed())
        val seals = items.first { it.id == "MAC-SEALS" } // matched by shot id
        assertEquals("Тюлени", seals.title)
        assertFalse(seals.highFrameRate)
        assertTrue(seals.masterUrl.endsWith("SE_A016_C009_SDR_20190717_SDR_4K_HEVC.mov"))
        assertTrue(seals.previewUrl!!.endsWith("_2K_AVC.mov"))
        val africa = items.first { it.id == "MAC-AFRICA" } // matched by file stem
        assertFalse(africa.highFrameRate)
        // Twins are not listed twice; tvOS-only scenes are kept.
        assertFalse(items.any { it.id == "TV-SEALS" || it.id == "TV-AFRICA" })
        assertEquals("Исландия", items.first { it.id == "TV-ONLY" }.title)
        assertFalse(items.any { it.id == "NO-VIDEO" })
    }

    /** Only scenes with a 4K master are offered: nothing softer ever becomes a wallpaper. */
    @Test
    fun onlyScenesWithA4kMasterAreListed() {
        val items = aerials.merge(macFeed(), tvFeed())
        assertFalse("1080p-only scenes are left out", items.any { it.id == "HD-ONLY" })
        // Every master must be one of the feeds' 4K files.
        val fourK = Regex("\"url-4K-SDR(?:-240FPS)?\": \"([^\"]+)\"")
            .findAll(tvEntries + macEntries).map { it.groupValues[1] }.toSet()
        for (item in items) assertTrue("${item.id} master must be 4K: ${item.masterUrl}", item.masterUrl in fourK)
    }

    /** "All" starts with one scene of each category, not with every landscape. */
    @Test
    fun allMixesTheCategories() {
        val items = aerials.merge(macFeed(), tvFeed())
        val categories = items.map { it.category }.distinct()
        assertEquals("the first scenes cover every category", categories.toSet(), items.take(categories.size).map { it.category }.toSet())
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }

    @Test
    fun worksWithASingleFeed() {
        assertEquals(3, aerials.merge(null, tvFeed()).size)
        assertEquals(4, aerials.merge(macFeed(), null).size)
    }

    @Test
    fun extractsFileFromTar() {
        val tar = tarOf("TVIdleScreenStrings.bundle/Info.plist" to "plist".encodeToByteArray(), "entries.json" to tvEntries.encodeToByteArray())
        assertEquals(tvEntries, TarReader.extract(ByteArrayInputStream(tar), "entries.json")?.decodeToString())
        assertNull(TarReader.extract(ByteArrayInputStream(tar), "missing.json"))
    }

    private fun tarOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in files) {
            val header = ByteArray(512)
            name.encodeToByteArray().copyInto(header, 0)
            "%011o".format(data.size).encodeToByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            out.write(header)
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }
}
