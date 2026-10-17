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
import org.junit.Assert.assertNull
import org.junit.Test

class AppleAerialsTest {

    private val aerials = AppleAerials(OkHttpClient(), Json { ignoreUnknownKeys = true }, File("build/tmp"))

    // Shape of tvOS resources-16 entries.json (trimmed).
    private val entries = """
        {
          "version": 1,
          "categories": [
            {"id": "U", "localizedNameKey": "AerialCategoryUnderwater"},
            {"id": "L", "localizedNameKey": "AerialCategoryLandscapes"}
          ],
          "assets": [
            {
              "id": "A",
              "accessibilityLabel": "Iceland",
              "categories": ["L"],
              "shotID": "I003_C008",
              "url-4K-SDR": "https://sylvan.apple.com/Aerials/2x/Videos/I003_C008_SDR_4K_HEVC.mov",
              "url-1080-H264": "https://sylvan.apple.com/Videos/I003_C008_2K_AVC.mov",
              "pointsOfInterest": {"0": "I003_C008_0"}
            },
            {
              "id": "B",
              "accessibilityLabel": "Seals",
              "categories": ["U"],
              "url-4K-SDR": "https://sylvan.apple.com/Aerials/2x/Videos/SE_A016_C009_SDR_4K_HEVC.mov",
              "url-1080-SDR": "https://sylvan.apple.com/Aerials/2x/Videos/SE_A016_C009_SDR_2K_HEVC.mov"
            },
            {"id": "C", "accessibilityLabel": "No video"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesAssetsWithRussianTitlesAndCategories() {
        val items = aerials.parse(entries.encodeToByteArray())
        assertEquals(listOf("A", "B"), items.map { it.id })
        val iceland = items.first { it.id == "A" }
        assertEquals("Исландия", iceland.title)
        assertEquals(FootageCategory.Landscapes, iceland.category)
        assertEquals("https://sylvan.apple.com/Videos/I003_C008_2K_AVC.mov", iceland.previewUrl)
        val seals = items.first { it.id == "B" }
        assertEquals("Морские котики", seals.title)
        assertEquals(FootageCategory.Underwater, seals.category)
        assertEquals(seals.previewUrl, "https://sylvan.apple.com/Aerials/2x/Videos/SE_A016_C009_SDR_2K_HEVC.mov")
    }

    @Test
    fun extractsFileFromTar() {
        val tar = tarOf("TVIdleScreenStrings.bundle/Info.plist" to "plist".encodeToByteArray(), "entries.json" to entries.encodeToByteArray())
        val extracted = TarReader.extract(ByteArrayInputStream(tar), "entries.json")
        assertEquals(entries, extracted?.decodeToString())
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
