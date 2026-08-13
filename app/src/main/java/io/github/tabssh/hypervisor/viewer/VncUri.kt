package io.github.tabssh.hypervisor.viewer

/**
 * Parser for `vnc://` URIs.
 *
 * Follows RFC 7869 (the `vnc://` URI scheme) plus the userinfo convention
 * TigerVNC, RealVNC and most web consoles emit:
 *
 * ```
 * vnc://host
 * vnc://host:5901
 * vnc://user@host:5901
 * vnc://user:secret@host:5901
 * vnc://host:5901?password=secret
 * ```
 *
 * The port defaults to [DEFAULT_PORT] when the authority carries none, as
 * RFC 7869 §2 requires. The port is always a real TCP port — a bare display
 * number (`host:1` meaning 5901) is deliberately *not* inferred, because
 * "1" is also a legal, if unusual, port and silently dialling a different
 * one than the URI names is worse than being strict.
 *
 * Recognised query parameters: `port`, `password`, `username`, `title`.
 * Everything else is ignored.
 *
 * ## Trust model
 *
 * Identical to [SpiceUri]: the URI is untrusted, so every length is capped,
 * every port range-checked, and the host rejected if it carries whitespace,
 * a control character, or a separator. A password carried by the URI is
 * used once for the RFB handshake and is never persisted or logged — see
 * [VirtViewerConnection.toString], which redacts it.
 *
 * Framework-free, so plain JVM unit tests can exercise it.
 */
object VncUri {

    /** URI scheme this parser handles. */
    const val SCHEME = "vnc"

    /** Port used when the URI names none (RFC 7869 §2). */
    const val DEFAULT_PORT = 5900

    /**
     * Largest password accepted. RFB truncates a VNC-auth password to eight
     * bytes, so anything remotely this long is malformed input rather than a
     * credential.
     */
    const val MAX_PASSWORD_LEN = 1024

    private val SCHEMES = setOf(SCHEME)

    /**
     * True when `uri` carries the `vnc` scheme.
     */
    @JvmStatic
    fun isVncUri(uri: String): Boolean = UriParsing.schemeOf(uri) == SCHEME

    /**
     * Parse `uri` into a [VirtViewerConnection] of type
     * [VirtViewerType.VNC].
     *
     * @throws VirtViewerParseException when the URI is not a usable VNC
     *   target. The message is safe to display and never contains the
     *   password value.
     */
    @JvmStatic
    fun parse(uri: String): VirtViewerConnection {
        val (_, authority, rawQuery) = UriParsing.splitUri(uri, SCHEMES)

        // Userinfo is split at the LAST '@' so a user name containing an
        // encoded '@' cannot move the host boundary.
        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        if (hostPort.isEmpty()) throw VirtViewerParseException("missing host")

        val (rawHost, authorityPort) = UriParsing.splitHostPort(hostPort)
        val host = UriParsing.host(rawHost)
        val params = UriParsing.query(rawQuery)

        var port = if (authorityPort != 0) authorityPort else DEFAULT_PORT
        params["port"]?.let { port = UriParsing.port("port", it) }
        if (port == 0) throw VirtViewerParseException("'port' out of range")

        val userSeparator = userinfo.indexOf(':')
        val rawUser = if (userSeparator >= 0) userinfo.substring(0, userSeparator) else userinfo
        val rawPassword = if (userSeparator >= 0) userinfo.substring(userSeparator + 1) else ""

        val username = UriParsing.decode(rawUser).takeIf { it.isNotEmpty() }
            ?: params["username"]?.takeIf { it.isNotEmpty() }
        val password = UriParsing.decode(rawPassword).takeIf { it.isNotEmpty() }
            ?: params["password"]?.takeIf { it.isNotEmpty() }

        if (username != null && username.length > UriParsing.MAX_HOST_LEN) {
            throw VirtViewerParseException("'username' too long")
        }
        if (password != null && password.length > MAX_PASSWORD_LEN) {
            throw VirtViewerParseException("password too long")
        }

        return VirtViewerConnection(
            type = VirtViewerType.VNC,
            host = host,
            port = port,
            tlsPort = 0,
            password = password,
            username = username,
            title = params["title"]?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Parse `uri`, returning null instead of throwing.
     */
    @JvmStatic
    fun parseOrNull(uri: String): VirtViewerConnection? =
        try {
            parse(uri)
        } catch (e: VirtViewerParseException) {
            null
        }
}
