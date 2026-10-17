package com.wallper.live.data.catalog

import java.nio.ByteBuffer

/** Just enough of Apple's property-list formats to read the Aerials manifests. */
object Plists {

    /** Parses a binary ("bplist00") property list into Kotlin maps, lists and scalars. */
    fun parseBinary(bytes: ByteArray): Any? {
        require(bytes.size > 40 && String(bytes, 0, 8, Charsets.US_ASCII) == "bplist00") { "Not a binary plist" }
        val buffer = ByteBuffer.wrap(bytes)
        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val refSize = bytes[trailer + 7].toInt() and 0xff
        val objectCount = buffer.getLong(trailer + 8).toInt()
        val topObject = buffer.getLong(trailer + 16).toInt()
        val tableOffset = buffer.getLong(trailer + 24).toInt()
        val offsets = IntArray(objectCount) { readUInt(bytes, tableOffset + it * offsetSize, offsetSize).toInt() }

        fun read(index: Int, depth: Int = 0): Any? {
            check(depth < 64) { "Plist too deep" }
            var p = offsets[index]
            val marker = bytes[p++].toInt() and 0xff
            val type = marker shr 4
            val info = marker and 0x0f

            fun length(): Int {
                if (info != 0x0f) return info
                val intMarker = bytes[p++].toInt() and 0xff
                val n = 1 shl (intMarker and 0x0f)
                val value = readUInt(bytes, p, n).toInt()
                p += n
                return value
            }

            return when (type) {
                0x0 -> when (info) { 0x8 -> false; 0x9 -> true; else -> null }
                0x1 -> readUInt(bytes, p, 1 shl info)
                0x2 -> if (info == 3) buffer.getDouble(p) else buffer.getFloat(p).toDouble()
                0x4 -> { val n = length(); bytes.copyOfRange(p, p + n) }
                0x5 -> { val n = length(); String(bytes, p, n, Charsets.US_ASCII) }
                0x6 -> { val n = length(); String(bytes, p, n * 2, Charsets.UTF_16BE) }
                0xA -> {
                    val n = length()
                    List(n) { read(readUInt(bytes, p + it * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val n = length()
                    val map = LinkedHashMap<String, Any?>(n)
                    for (i in 0 until n) {
                        val key = read(readUInt(bytes, p + i * refSize, refSize).toInt(), depth + 1)
                        val value = read(readUInt(bytes, p + (n + i) * refSize, refSize).toInt(), depth + 1)
                        map[key.toString()] = value
                    }
                    map
                }
                else -> null
            }
        }
        return read(topObject)
    }

    /** Reads a string value from either an XML or a binary plist dictionary. */
    fun stringValue(bytes: ByteArray, key: String): String? {
        if (bytes.size > 8 && String(bytes, 0, 8, Charsets.US_ASCII) == "bplist00") {
            return (parseBinary(bytes) as? Map<*, *>)?.get(key) as? String
        }
        val xml = bytes.decodeToString()
        val match = Regex("<key>\\s*${Regex.escape(key)}\\s*</key>\\s*<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(xml) ?: return null
        return match.groupValues[1].trim()
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    }

    /**
     * Reads a Localizable.strings table, which ships either as a binary plist or as
     * text (`"key" = "value";`) in UTF-16 or UTF-8.
     */
    fun parseStrings(bytes: ByteArray): Map<String, String> {
        if (bytes.size > 8 && String(bytes, 0, 8, Charsets.US_ASCII) == "bplist00") {
            return (parseBinary(bytes) as? Map<*, *>).orEmpty()
                .mapNotNull { (k, v) -> (v as? String)?.let { k.toString() to it } }
                .toMap()
        }
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16)
            else -> bytes.decodeToString()
        }
        return STRINGS_ENTRY.findAll(text).associate { unescape(it.groupValues[1]) to unescape(it.groupValues[2]) }
    }

    private val STRINGS_ENTRY = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;")

    private fun unescape(value: String) = value
        .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun readUInt(bytes: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        for (i in 0 until size) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        return value
    }
}
