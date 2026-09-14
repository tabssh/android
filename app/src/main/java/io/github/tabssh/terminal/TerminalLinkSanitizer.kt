package io.github.tabssh.terminal

/**
 * The single validation point for server-supplied OSC 8 hyperlink targets.
 *
 * A remote can emit any bytes it likes as an OSC 8 target, and the tap handler
 * ultimately routes an unrecognised scheme to an `ACTION_VIEW` intent, so an
 * unchecked target lets a hostile server aim the user at `intent://`,
 * `javascript:`, `file://` or `content://` from what looks like ordinary
 * output. This is a security control, and it previously existed as two
 * independent copies — one in `TermuxBridge`, one in `ANSIParser` — each with
 * its own allowlist constant. Divergence between the copies was the real risk:
 * a scheme added to one list and not the other silently left one path
 * permissive. Both paths now share this object.
 */
object TerminalLinkSanitizer {

    /**
     * Upper bound on a tracked link target. A hostile server can emit an
     * arbitrarily long URI; anything past this is not a usable link, only
     * memory pressure and an unreadable confirmation dialog.
     */
    const val MAX_URL_LENGTH = 2048

    /**
     * Schemes a remote is allowed to hand us through OSC 8. Anything else is
     * dropped rather than passed to an intent.
     */
    val ALLOWED_SCHEMES = setOf(
        "http", "https", "ftp", "ftps", "ssh", "sftp", "telnet", "mailto"
    )

    /**
     * Validate a server-supplied link target.
     *
     * @return the trimmed URL when it is safe to surface to the user, or null
     *     when it must be dropped.
     */
    fun sanitize(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        // Control characters and DEL cannot appear in a usable URI and are the
        // classic vector for smuggling terminal escapes back through a link or
        // spoofing the "open this link" dialog. Hoisted out of the `if` because
        // Android lint's SuspiciousIndentation check reports a false positive on
        // the inline form and `abortOnError` fails the build on it.
        val hasControlChars = trimmed.any { it.code < 0x20 || it.code == 0x7F }
        if (hasControlChars) return null
        val scheme = trimmed.substringBefore(':', "").lowercase()
        if (scheme.isEmpty() || scheme !in ALLOWED_SCHEMES) return null
        return trimmed
    }
}
