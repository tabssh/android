package io.github.tabssh.utils

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Reads UTF-8 input up to a strict byte limit, rejecting rather than truncating overflow. */
internal object BoundedTextReader {

    const val MAX_TEXT_IMPORT_BYTES = 16 * 1024 * 1024
    const val MAX_KEY_FILE_BYTES = 1024 * 1024

    fun readUtf8(input: InputStream, maxBytes: Int): String {
        return readBytes(input, maxBytes).toString(Charsets.UTF_8)
    }

    fun readBytes(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0 && maxBytes < Int.MAX_VALUE) { "maxBytes must be between 1 and Int.MAX_VALUE - 1" }

        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
        val buffer = ByteArray(minOf(maxBytes + 1, BUFFER_SIZE))
        var total = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - total))
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                if (total == maxBytes) throw IOException("Input exceeds the $maxBytes byte limit")
                output.write(next)
                total++
                continue
            }
            total += count
            if (total > maxBytes) throw IOException("Input exceeds the $maxBytes byte limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private const val BUFFER_SIZE = 8 * 1024
}
