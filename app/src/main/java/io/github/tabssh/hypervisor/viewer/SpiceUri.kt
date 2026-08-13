package io.github.tabssh.hypervisor.viewer

/**
 * Parser for `spice://` and `spice+tls://` URIs.
 *
 * These follow the conventions `remote-viewer` documents:
 *
 * ```
 * spice://host:5900
 * spice://host:5900?password=s3cret
 * spice://host/?tls-port=5901
 * spice+tls://host:5901
 * ```
 *
 * `spice://` names a plain-text port either in the authority or in the
 * `port` query parameter. `spice+tls://` names a TLS port the same way. A
 * `tls-port` query parameter adds (or, with no authority port, supplies)
 * the TLS port for either scheme — that is the shape virt-manager emits
 * for a TLS-only display.
 *
 * Recognised query parameters: `port`, `tls-port`, `password`, `ca`,
 * `host-subject`, `proxy`, `title`. Everything else is ignored, matching
 * virt-viewer's behaviour with unknown options.
 *
 * ## Trust model
 *
 * A URI reaches this parser from an untrusted source. It is bounded and
 * validated through [UriParsing] to the same standard as [VirtViewerFile]:
 * length caps, ports constrained to 1..65535, host names rejected if they
 * carry whitespace, control characters, or separators.
 *
 * Passing a password in a URI is discouraged (it lands in browser history
 * and in the intent that launches the app), but virt-viewer accepts it and
 * some hypervisor web UIs emit it, so it is honoured — never logged, never
 * persisted.
 *
 * This object is free of Android framework types so plain JVM unit tests
 * can exercise it; in particular it does not use `android.net.Uri`, whose
 * unit-test stubs throw.
 */
object SpiceUri {

    /** Plain-text scheme. */
    const val SCHEME_PLAIN = "spice"

    /** TLS scheme. */
    const val SCHEME_TLS = "spice+tls"

    /** Largest URI accepted, in characters. */
    const val MAX_URI_LEN = UriParsing.MAX_URI_LEN

    private val SCHEMES = setOf(SCHEME_PLAIN, SCHEME_TLS)

    /**
     * True when `uri` carries a scheme this parser handles. Cheap enough
     * for an intent-filter double-check at the call site.
     */
    @JvmStatic
    fun isSpiceUri(uri: String): Boolean = UriParsing.schemeOf(uri) in SCHEMES

    /**
     * Parse `uri` into a [VirtViewerConnection] of type
     * [VirtViewerType.SPICE].
     *
     * @throws VirtViewerParseException when the URI is not a usable SPICE
     *   target. The message is safe to display and never contains the
     *   password value.
     */
    @JvmStatic
    fun parse(uri: String): VirtViewerConnection {
        val (scheme, authority, rawQuery) = UriParsing.splitUri(uri, SCHEMES)
        if (authority.contains('@')) {
            throw VirtViewerParseException("userinfo is not supported in a spice URI")
        }

        val (rawHost, authorityPort) = UriParsing.splitHostPort(authority)
        val host = UriParsing.host(rawHost)
        val params = UriParsing.query(rawQuery)

        val tlsScheme = scheme == SCHEME_TLS
        var port = if (tlsScheme) 0 else authorityPort
        var tlsPort = if (tlsScheme) authorityPort else 0

        params["port"]?.let { port = UriParsing.port("port", it) }
        params["tls-port"]?.let { tlsPort = UriParsing.port("tls-port", it) }

        if (port == 0 && tlsPort == 0) throw VirtViewerParseException("no port given")

        return VirtViewerConnection(
            type = VirtViewerType.SPICE,
            host = host,
            port = port,
            tlsPort = tlsPort,
            password = params["password"]?.takeIf { it.isNotEmpty() },
            caCert = params["ca"]?.takeIf { it.isNotBlank() },
            hostSubject = params["host-subject"]?.takeIf { it.isNotBlank() },
            proxy = params["proxy"]?.takeIf { it.isNotBlank() },
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
