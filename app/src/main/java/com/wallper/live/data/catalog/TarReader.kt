package com.wallper.live.data.catalog

import java.io.EOFException
import java.io.InputStream

/** Minimal streaming reader for POSIX/ustar archives. */
object TarReader {

    private const val BLOCK = 512

    /** Returns the content of the first regular file whose name matches [name]. */
    fun extract(input: InputStream, name: String): ByteArray? {
        val header = ByteArray(BLOCK)
        while (true) {
            if (!readFully(input, header)) return null
            if (header.all { it == 0.toByte() }) return null
            val entryName = cString(header, 0, 100)
            val prefix = cString(header, 345, 155)
            val fullName = if (prefix.isEmpty()) entryName else "$prefix/$entryName"
            val size = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val padded = (size + BLOCK - 1) / BLOCK * BLOCK
            if ((type == '0' || type == '\u0000') && fullName.removePrefix("./") == name) {
                val data = ByteArray(size.toInt())
                if (!readFully(input, data)) throw EOFException("Truncated tar entry $name")
                return data
            }
            skipFully(input, padded)
        }
    }

    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun parseOctal(bytes: ByteArray, offset: Int, length: Int): Long {
        var result = 0L
        for (i in offset until offset + length) {
            val c = bytes[i].toInt()
            if (c == 0 || c == ' '.code) continue
            result = result * 8 + (c - '0'.code)
        }
        return result
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return read == buffer.size
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val n = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (n < 0) throw EOFException()
                remaining -= n
            }
        }
    }
}
