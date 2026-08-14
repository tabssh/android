package io.github.tabssh.utils

/**
 * Classifies a detected terminal hyperlink (see TerminalView.urlPattern) into
 * the dialog behaviour it should drive in TabTerminalActivity.showUrlDialog.
 *
 * Kept free of Android framework types so the scheme/URL parsing here can be
 * covered by plain JVM unit tests; anything that needs PackageManager,
 * Activity, or Intent stays in TabTerminalActivity.
 */
object TerminalLinkClassifier {

    /**
     * The decision for a single detected link. `url` is always the original,
     * unmodified string that was tapped — dialogs echo it back to the user.
     */
    sealed class LinkAction {
        /** ssh://[user@]host[:port][/...] — offer to open a new session in-app. */
        data class Ssh(val url: String, val username: String?, val host: String, val port: Int) : LinkAction()

        /** sftp://[user@]host[:port][/path] — offer to browse the remote host over SFTP. */
        data class Sftp(val url: String, val username: String?, val host: String, val port: Int, val path: String) : LinkAction()

        /** file://[host]/path — path lives on the remote host, never the device. */
        data class RemoteFile(val url: String, val path: String) : LinkAction()

        /** git:// ftp:// ftps:// svn:// — "Open" only offered if a handler exists. */
        data class ExternalScheme(val url: String, val scheme: String) : LinkAction()

        /** http/https/www. — unchanged "opens in your browser" flow. */
        data class Browser(val url: String) : LinkAction()

        /**
         * A remote-supplied scheme that isn't on the allowlist (e.g. an
         * intent:, file:-with-authority-stripped-elsewhere, javascript:, or
         * content: OSC 8 hyperlink). Not a link — no dialog, no Intent is
         * ever built from it.
         */
        data class NotALink(val url: String) : LinkAction()
    }

    // Matches the RFC 3986 scheme prefix of any URI, with or without "//" —
    // a "://"-only pattern would miss no-authority schemes like javascript:
    // or mailto:, letting them slip past the allowlist check below as "no
    // scheme detected".
    private val schemeRe = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    // Schemes a remote OSC 8 hyperlink is allowed to drive an action for.
    // Anything not covered by an explicit case below (intent:, javascript:,
    // content:, tel:, market:, …) is untrusted-remote-controlled input and
    // must never reach Intent.ACTION_VIEW — see LinkAction.NotALink.
    private val allowedSchemes = setOf(
        "http", "https", "ssh", "sftp", "file", "git", "ftp", "ftps", "svn", "telnet", "vnc", "spice"
    )

    /** Classifies [url] (as detected by TerminalView.urlPattern) into a [LinkAction]. */
    fun classify(url: String): LinkAction {
        val scheme = schemeRe.find(url)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme !in allowedSchemes) {
            // '.' is legal in a scheme per RFC 3986, so an OSC 8 href like
            // "example.com:8080/path" matches with "example.com" as its
            // "scheme". No real-world scheme contains a dot — when there is
            // no "://" following, this is host:port text, and it keeps the
            // pre-allowlist Browser behavior. A dotted scheme WITH "://"
            // stays rejected like any other non-allowlisted scheme.
            val isHostPortText = scheme.contains('.') &&
                !url.regionMatches(scheme.length, "://", 0, 3, ignoreCase = true)
            return if (isHostPortText) LinkAction.Browser(url) else LinkAction.NotALink(url)
        }
        return when (scheme) {
            "ssh" -> parseSsh(url)?.let { (username, host, port) -> LinkAction.Ssh(url, username, host, port) }
                ?: LinkAction.Browser(url)
            "sftp" -> parseSftp(url)?.let { (username, host, port, path) -> LinkAction.Sftp(url, username, host, port, path) }
                ?: LinkAction.Browser(url)
            "file" -> LinkAction.RemoteFile(url, extractFilePath(url))
            "git", "ftp", "ftps", "svn", "telnet", "vnc", "spice" -> LinkAction.ExternalScheme(url, scheme)
            else -> LinkAction.Browser(url)
        }
    }

    /** Parsed sftp:// authority + optional remote path (see [parseSftp]). */
    data class SftpTarget(val username: String?, val host: String, val port: Int, val path: String)

    /**
     * Parses an ssh:// authority into (username, host, port). Username is
     * optional (null when absent); port defaults to 22 when not specified.
     * Returns null when no host can be extracted (e.g. "ssh://" alone).
     */
    fun parseSsh(url: String): Triple<String?, String, Int>? {
        val withoutScheme = url.replaceFirst(Regex("(?i)^ssh://"), "")
        val authority = withoutScheme.substringBefore("/").substringBefore("?").substringBefore("#")
        return parseAuthority(authority)
    }

    /**
     * Parses an sftp:// URL into (username, host, port, path). Username and
     * port follow the same rules as [parseSsh]; the path defaults to "/"
     * when absent and is percent-decoded. Returns null when no host can be
     * extracted (e.g. "sftp://" alone).
     */
    fun parseSftp(url: String): SftpTarget? {
        val withoutScheme = url.replaceFirst(Regex("(?i)^sftp://"), "")
        val authority = withoutScheme.substringBefore("/").substringBefore("?").substringBefore("#")
        val (username, host, port) = parseAuthority(authority) ?: return null

        val rawPath = withoutScheme.substring(authority.length).substringBefore("?").substringBefore("#")
        val path = if (rawPath.isBlank()) {
            "/"
        } else {
            try {
                java.net.URLDecoder.decode(rawPath, "UTF-8")
            } catch (e: Exception) {
                rawPath
            }
        }
        return SftpTarget(username, host, port, path)
    }

    /**
     * Shared ssh:// / sftp:// authority parser: "[user@]host[:port]" (or
     * "[user@][::ipv6]:port]" for bracketed IPv6 literals) -> (username,
     * host, port). Port defaults to 22 when absent. Returns null when the
     * authority is blank or no host can be extracted.
     */
    private fun parseAuthority(authority: String): Triple<String?, String, Int>? {
        if (authority.isBlank()) return null

        val atIdx = authority.lastIndexOf('@')
        val username = if (atIdx >= 0) authority.substring(0, atIdx).takeIf { it.isNotBlank() } else null
        val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
        if (hostPort.isBlank()) return null

        // IPv6 literals are bracketed, e.g. [::1]:2222 — don't split on the
        // colons inside the brackets.
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val closeIdx = hostPort.indexOf(']')
            if (closeIdx < 0) return null
            host = hostPort.substring(1, closeIdx)
            val rest = hostPort.substring(closeIdx + 1)
            port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: 22 else 22
        } else {
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx > 0) {
                host = hostPort.substring(0, colonIdx)
                port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: 22
            } else {
                host = hostPort
                port = 22
            }
        }
        if (host.isBlank()) return null
        return Triple(username, host, port)
    }

    /**
     * Extracts the remote path portion of a file:// URL, decoding percent
     * escapes. `file:///path` and `file://host/path` (an authority component)
     * are both supported — the authority, if present, is discarded since the
     * dialog only ever offers the path itself.
     */
    fun extractFilePath(url: String): String {
        val withoutScheme = url.replaceFirst(Regex("(?i)^file://"), "")
        val rawPath = if (withoutScheme.startsWith("/")) {
            withoutScheme
        } else {
            val slashIdx = withoutScheme.indexOf('/')
            if (slashIdx >= 0) withoutScheme.substring(slashIdx) else "/"
        }
        return try {
            java.net.URLDecoder.decode(rawPath, "UTF-8")
        } catch (e: Exception) {
            rawPath
        }
    }
}
