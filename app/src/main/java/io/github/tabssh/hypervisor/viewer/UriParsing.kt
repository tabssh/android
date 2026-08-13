package io.github.tabssh.hypervisor.viewer

import java.io.UnsupportedEncodingException
import java.net.URLDecoder

/**
 * Shared, framework-free pieces of the display-URI parsers ([SpiceUri] and
 * [VncUri]).
 *
 * Everything here is written for hostile input: a URI reaching the app from
 * a tapped link, a QR code, or another app's intent. The helpers bound
 * lengths, range-check ports, and reject host values that could break out
 * of the field they are later used in.
 */
internal object UriParsing {

    /** Largest URI accepted, in characters. */
    const val MAX_URI_LEN = 64 * 1024

    /** Largest accepted decoded value for a single query parameter. */
    const val MAX_PARAM_LEN = 64 * 1024

    /** Largest accepted host name, kept in step with [VirtViewerFile]. */
    const val MAX_HOST_LEN = VirtViewerFile.MAX_HOST_LEN

    /**
     * The scheme of `uri`, lowercased, or the empty string when it carries
     * no `scheme://` prefix.
     */
    fun schemeOf(uri: String): String =
        uri.substringBefore("://", missingDelimiterValue = "").lowercase()

    /**
     * Split a `scheme://…` URI into its authority (host and optional port,
     * userinfo included) and its raw query string, discarding the path and
     * the fragment — neither carries meaning for a display URI.
     *
     * @throws VirtViewerParseException when the URI is oversized, carries no
     *   scheme separator, or has an empty authority.
     */
    fun splitUri(uri: String, expectedSchemes: Set<String>): Triple<String, String, String> {
        if (uri.length > MAX_URI_LEN) throw VirtViewerParseException("URI too long")

        val schemeEnd = uri.indexOf("://")
        if (schemeEnd <= 0) throw VirtViewerParseException("not a display URI")

        val scheme = uri.substring(0, schemeEnd).lowercase()
        if (scheme !in expectedSchemes) {
            throw VirtViewerParseException("unsupported scheme '${sanitize(scheme)}'")
        }

        val rest = uri.substring(schemeEnd + 3)
        val withoutFragment = rest.substringBefore('#')
        val authority = withoutFragment.substringBefore('?').substringBefore('/')
        if (authority.isEmpty()) throw VirtViewerParseException("missing host")

        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        return Triple(scheme, authority, query)
    }

    /**
     * Split `host:port`, honouring the `[::1]:5900` bracket form for IPv6
     * literals. A missing port yields 0; a malformed one is an error rather
     * than a silent fallback, because guessing a port for a URI the user
     * tapped is how a connection ends up somewhere unintended.
     */
    fun splitHostPort(authority: String): Pair<String, Int> {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) throw VirtViewerParseException("malformed IPv6 host")
            val host = authority.substring(1, close)
            val tail = authority.substring(close + 1)
            if (tail.isEmpty()) return host to 0
            if (!tail.startsWith(":")) throw VirtViewerParseException("malformed IPv6 host")
            return host to port("port", tail.substring(1))
        }

        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to 0
        return authority.substring(0, colon) to port("port", authority.substring(colon + 1))
    }

    /**
     * Percent-decode and validate a host taken from a URI authority.
     * Decoding happens before validation so an encoded control character or
     * separator cannot slip past as literal "%00" text.
     */
    fun host(raw: String): String {
        val host = decode(raw)
        if (host.isEmpty()) throw VirtViewerParseException("missing host")
        if (host.length > MAX_HOST_LEN) throw VirtViewerParseException("host too long")
        val plausible = host.none {
            it.isWhitespace() || it.isISOControl() ||
                it == '/' || it == '\\' || it == '@' || it == '"'
        }
        if (!plausible) throw VirtViewerParseException("invalid host")
        return host
    }

    /**
     * Decode a query string into lowercase parameter names. A repeated
     * parameter takes its last value, matching how GLib option parsing
     * behaves.
     */
    fun query(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = decode(pair.substring(0, eq)).trim().lowercase()
            if (name.isEmpty()) continue
            val value = decode(pair.substring(eq + 1))
            if (value.length > MAX_PARAM_LEN) {
                throw VirtViewerParseException("value for '$name' too long")
            }
            out[name] = value
        }
        return out
    }

    /**
     * Parse and range-check a port. An empty value yields 0 ("not given").
     */
    fun port(name: String, raw: String): Int {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return 0
        val value = trimmed.toIntOrNull()
            ?: throw VirtViewerParseException("'$name' is not a number")
        if (value !in 1..65535) throw VirtViewerParseException("'$name' out of range")
        return value
    }

    /**
     * Percent-decode a URI component. A malformed escape is left as-is
     * rather than aborting the parse — the value still has to survive
     * validation afterwards.
     */
    fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            value
        } catch (e: UnsupportedEncodingException) {
            value
        }

    /**
     * Strip control characters before a value is quoted into a user-visible
     * error message.
     */
    fun sanitize(value: String): String =
        value.take(32).filter { !it.isISOControl() }
}
