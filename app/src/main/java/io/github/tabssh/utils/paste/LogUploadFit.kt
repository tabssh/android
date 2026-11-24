package io.github.tabssh.utils.paste

/**
 * Brings a log under an upload size limit keeping what matters: the header, then the most RECENT
 * entries. Cutting from the end keeps the oldest lines and throws away the very activity being
 * reported. Older rotated logs are dropped first, then the oldest lines of the current log, and a
 * marker says that lines were left out.
 */
object LogUploadFit {

    private const val HEADER_END = "========================"
    private const val ROTATED = "=== Rotated log"
    private const val MARKER = "[older lines omitted to fit the upload limit; the most recent entries follow]\n"

    fun fit(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val headerEnd = text.indexOf(HEADER_END).let { i ->
            if (i < 0) 0 else text.indexOf('\n', i).let { if (it < 0) 0 else it + 1 }
        }
        val header = text.substring(0, headerEnd)
        var body = text.substring(headerEnd)
        val rotated = body.indexOf(ROTATED)
        if (rotated > 0) body = body.substring(0, rotated)
        val budget = maxBytes - header.toByteArray(Charsets.UTF_8).size - MARKER.toByteArray(Charsets.UTF_8).size
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size <= budget) return header + body
        var tail = String(bytes, bytes.size - budget, budget, Charsets.UTF_8)
        // Start on a whole line, not in the middle of one or of a multi-byte character.
        val nl = tail.indexOf('\n')
        if (nl >= 0) tail = tail.substring(nl + 1)
        return header + MARKER + tail
    }
}
