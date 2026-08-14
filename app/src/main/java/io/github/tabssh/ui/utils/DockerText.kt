package io.github.tabssh.ui.utils

/**
 * Display sanitizer for server-controlled Docker/compose strings.
 *
 * Container names, image labels, network/volume names, compose output and
 * daemon error bodies all originate on the remote host and are rendered
 * verbatim in list rows, action sheets, dialogs and toasts. A hostile or
 * merely careless remote can embed ANSI/C0 escapes (terminal manipulation
 * when the same text is echoed to a console tab) or Unicode bidi overrides
 * (visually reordering a name so a destructive confirmation dialog reads as
 * a different container than the one that will actually be removed).
 *
 * Mirrors the sanitizer already applied to remote terminal titles in the
 * console audit: drop C0/C1 controls and bidi overrides, collapse line
 * breaks for single-line widgets, and cap the length so one pathological
 * label cannot blow up layout or memory.
 */
object DockerText {

    /** Default cap for single-line widgets — matches the console title cap. */
    const val MAX_DISPLAY_LENGTH = 256

    /** Cap for multi-line blobs (inspect JSON, compose logs) rendered in a dialog. */
    const val MAX_BLOCK_LENGTH = 64 * 1024

    private const val ELLIPSIS = "…"

    // Complete ANSI escape sequences — CSI (ESC[ params final byte), OSC
    // (ESC] … BEL/ST), and single-char ESC sequences. Removed as whole
    // units BEFORE the per-char control strip: dropping only the ESC byte
    // would leave the printable remainder ("[2J", "[31m") as visible
    // garbage in labels and log dialogs. ESC-initiated only — a bare C1
    // 0x9B usually comes from charset misdecoding, so treating it as a CSI
    // introducer would eat the legitimate character after it; the per-char
    // strip drops the lone byte instead.
    private val ANSI_SEQUENCE = Regex(
        "\\u001B\\[[0-?]*[ -/]*[@-~]" +
            "|\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)?" +
            "|\\u001B[@-_]"
    )

    /**
     * Returns [raw] safe for a single-line label: control characters and bidi
     * overrides removed, line breaks collapsed to spaces, trimmed, and capped
     * at [max] characters with a trailing ellipsis when truncated.
     */
    fun display(raw: String?, max: Int = MAX_DISPLAY_LENGTH): String {
        if (raw.isNullOrEmpty()) return ""
        val stripped = ANSI_SEQUENCE.replace(raw, "")
        val builder = StringBuilder(minOf(stripped.length, max + 1))
        for (ch in stripped) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> builder.append(' ')
                isStripped(ch) -> Unit
                else -> builder.append(ch)
            }
            if (builder.length > max) break
        }
        val collapsed = builder.toString().trim()
        return if (collapsed.length > max) collapsed.take(max).trimEnd() + ELLIPSIS else collapsed
    }

    /**
     * Returns [raw] safe for a multi-line block (inspect output, compose logs):
     * line breaks preserved, control characters and bidi overrides removed,
     * capped at [max] characters.
     */
    fun block(raw: String?, max: Int = MAX_BLOCK_LENGTH): String {
        if (raw.isNullOrEmpty()) return ""
        // Cap first (a pathological blob should never feed the regex whole),
        // then drop complete escape sequences; a sequence cut in half by the
        // cap loses its ESC to the per-char strip below, leaving only
        // printable residue.
        val capped = if (raw.length > max) raw.substring(0, max) else raw
        val source = ANSI_SEQUENCE.replace(capped, "")
        val builder = StringBuilder(source.length)
        for (ch in source) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> builder.append(ch)
                isStripped(ch) -> Unit
                else -> builder.append(ch)
            }
        }
        val text = builder.toString()
        return if (raw.length > max) text + ELLIPSIS else text
    }

    /**
     * True when [ch] is a C0/C1 control character or a Unicode bidirectional
     * override/isolate that could visually reorder surrounding text.
     */
    private fun isStripped(ch: Char): Boolean = when {
        ch.code < 0x20 -> true
        ch.code == 0x7F -> true
        ch.code in 0x80..0x9F -> true
        ch.code in 0x202A..0x202E -> true
        ch.code in 0x2066..0x2069 -> true
        else -> false
    }
}
