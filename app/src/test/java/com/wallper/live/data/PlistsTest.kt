package com.wallper.live.data

import com.wallper.live.data.catalog.Plists
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class PlistsTest {

    // Generated with Python's plistlib (FMT_BINARY).
    private val config = Base64.getDecoder().decode(
        "YnBsaXN0MDDTAQIDBAcIVGxpc3RRbl1yZXNvdXJjZXMtdXJsogUGZAQiBDAERQQ+UWEQKl8QL2h0dHBzOi8vc3lsdmFuLmFwcGxlLmNv" +
            "bS94L3Jlc291cmNlcy0yNi00LTEudGFyCA8UFiQnMDI0AAAAAAAAAQEAAAAAAAAACQAAAAAAAAAAAAAAAAAAAGY=",
    )
    private val strings = Base64.getDecoder().decode(
        "YnBsaXN0MDDSAQIDBF8QFkFlcmlhbFN1YmNhdGVnb3J5VGFob2VeVEFIT0VfREFZX05BTUVkBCIEMARFBD5pBCIEMARFBD4AIAQ0BD0E" +
            "UQQ8CA0mNT4AAAAAAAABAQAAAAAAAAAFAAAAAAAAAAAAAAAAAAAAUQ==",
    )

    @Test
    fun parsesBinaryPlist() {
        val root = Plists.parseBinary(config) as Map<*, *>
        assertEquals("https://sylvan.apple.com/x/resources-26-4-1.tar", root["resources-url"])
        assertEquals(42L, root["n"])
        assertEquals(listOf("Тахо", "a"), root["list"])
        assertEquals("https://sylvan.apple.com/x/resources-26-4-1.tar", Plists.stringValue(config, "resources-url"))
    }

    @Test
    fun readsXmlPlistValue() {
        val xml = """<?xml version="1.0"?><plist version="1.0"><dict>
            <key>resources-url</key><string>https://a.example/r.tar?x=1&amp;y=2</string></dict></plist>"""
        assertEquals("https://a.example/r.tar?x=1&y=2", Plists.stringValue(xml.encodeToByteArray(), "resources-url"))
    }

    @Test
    fun parsesBinaryAndTextStringsTables() {
        assertEquals(mapOf("AerialSubcategoryTahoe" to "Тахо", "TAHOE_DAY_NAME" to "Тахо днём"), Plists.parseStrings(strings))
        val text = "/* c */\n\"A\" = \"Б \\\"в\\\"\";\n\"K\"=\"V\";".toByteArray(Charsets.UTF_16)
        assertEquals(mapOf("A" to "Б \"в\"", "K" to "V"), Plists.parseStrings(text))
    }
}
