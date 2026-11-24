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
    private const val KEPT_MARKER = "[warnings and errors from the omitted lines]\n"
    private val WARN_OR_ERROR = Regex("""^\S+ \S+ [WE]/""")

    /** Smallest upload worth retrying with. */
    const val MIN_UPLOAD_BYTES = 16 * 1024

    /** True when a paste service refused the body for size (HTTP 413 from every supported service). */
    fun isTooLarge(error: Throwable): Boolean {
        val msg = error.message.orEmpty()
        return msg.contains("HTTP 413") || msg.contains("exceeds the maximum", ignoreCase = true)
    }

    /** Size for the next attempt after a too-large refusal, or null when already at the minimum. */
    fun smaller(currentBytes: Int): Int? {
        if (currentBytes <= MIN_UPLOAD_BYTES) return null
        return maxOf(currentBytes / 2, MIN_UPLOAD_BYTES)
    }

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
        // A quarter of the room goes to warnings and errors from the dropped part, so a failure
        // that scrolled out under routine lines is still in the upload.
        val keptBudget = budget / 4
        val tailBudget = budget - keptBudget - KEPT_MARKER.toByteArray(Charsets.UTF_8).size
        var tail = String(bytes, bytes.size - tailBudget, tailBudget, Charsets.UTF_8)
        // Start on a whole line, not in the middle of one or of a multi-byte character.
        val nl = tail.indexOf('\n')
        if (nl >= 0) tail = tail.substring(nl + 1)
        val omitted = body.substring(0, body.length - tail.length)
        val kept = ArrayDeque<String>()
        var keptBytes = 0
        for (line in omitted.lines().asReversed()) {
            if (!WARN_OR_ERROR.containsMatchIn(line)) continue
            val size = line.toByteArray(Charsets.UTF_8).size + 1
            if (keptBytes + size > keptBudget) break
            kept.addFirst(line)
            keptBytes += size
        }
        if (kept.isEmpty()) return header + MARKER + String(bytes, bytes.size - budget, budget, Charsets.UTF_8).let { t ->
            val i = t.indexOf('\n')
            if (i >= 0) t.substring(i + 1) else t
        }
        return header + MARKER + KEPT_MARKER + kept.joinToString("\n", postfix = "\n") + tail
    }
}
