package io.github.tabssh.hypervisor.viewer

import io.github.tabssh.hypervisor.spice.SpiceConnectionParams

/**
 * Display protocol a virt-viewer connection descriptor asks for.
 *
 * virt-viewer's `type` key only ever carries `spice` or `vnc`; anything
 * else is rejected by the parsers rather than guessed at.
 */
enum class VirtViewerType {
    SPICE,
    VNC,
}

/**
 * A parsed virt-viewer connection descriptor — the common result type of
 * [VirtViewerFile] (`.vv` INI files), [SpiceUri] (`spice://` and
 * `spice+tls://` URIs) and [VncUri] (`vnc://` URIs).
 *
 * Field names follow virt-viewer's own key names so a reader can line the
 * struct up against a `.vv` file or the `remote-viewer` manual page.
 *
 * Everything in here originates outside the app — a file handed over by
 * another app, or a URI tapped in a browser — so the parsers bound every
 * length and range before constructing one of these. Consumers may treat
 * the values as well-formed but must still treat them as *untrusted*: a
 * well-formed host name is still an attacker-chosen host name.
 *
 * @property type Display protocol to open.
 * @property host Display server host. Never blank.
 * @property port Plain-text display port, or 0 when only TLS is offered.
 * @property tlsPort TLS display port, or 0 when TLS is not offered.
 *   At least one of [port] / [tlsPort] is always non-zero.
 * @property password Session ticket, or null when the descriptor carries
 *   none. Never logged, never persisted — see [toString].
 * @property username User name carried by a `vnc://user@host` URI, or null.
 *   Only VeNCrypt Plain sub-types use it; plain VNC auth has no user name
 *   and `.vv` files have no key for one.
 * @property caCert PEM text of the CA that signed the display server's
 *   certificate, with virt-viewer's escaped `\n` sequences already turned
 *   back into real newlines. Null when absent.
 * @property hostSubject Expected certificate subject to pin against, or
 *   null for CA-only validation.
 * @property proxy Proxy URL the hypervisor advertised, or null.
 * @property title Human-readable session title for the tab, or null.
 * @property deleteThisFile True when the descriptor asked to be deleted
 *   after a successful read (`delete-this-file=1`). Only meaningful for
 *   `.vv` files; always false for URIs.
 * @property fullscreen True when the descriptor asked for a fullscreen
 *   display.
 * @property enableUsbredir True when the descriptor asked for USB
 *   redirection. TabSSH has no USB redirection support, so this is
 *   recorded and ignored rather than silently dropped at parse time.
 * @property releaseCursor virt-viewer hotkey spec for releasing the
 *   pointer grab, or null. Recorded verbatim; TabSSH has no pointer grab.
 * @property secureAttention virt-viewer hotkey spec for the secure
 *   attention sequence (Ctrl+Alt+Del), or null.
 * @property toggleFullscreen virt-viewer hotkey spec for toggling
 *   fullscreen, or null.
 */
data class VirtViewerConnection(
    val type: VirtViewerType,
    val host: String,
    val port: Int,
    val tlsPort: Int,
    val password: String? = null,
    val username: String? = null,
    val caCert: String? = null,
    val hostSubject: String? = null,
    val proxy: String? = null,
    val title: String? = null,
    val deleteThisFile: Boolean = false,
    val fullscreen: Boolean = false,
    val enableUsbredir: Boolean = false,
    val releaseCursor: String? = null,
    val secureAttention: String? = null,
    val toggleFullscreen: String? = null,
) {
    /** True when this descriptor offers a TLS port. */
    val isTls: Boolean get() = tlsPort != 0

    /**
     * The port a client should actually dial: the plain-text port when one
     * is offered, otherwise the TLS port.
     */
    val effectivePort: Int get() = if (port != 0) port else tlsPort

    /**
     * Build [SpiceConnectionParams] for `SpiceClient`.
     *
     * `tlsVerify` is true whenever a [caCert] was supplied — that CA is the
     * whole reason the hypervisor put it in the descriptor, and ignoring it
     * would silently downgrade a chain we can validate. Without a CA there
     * is nothing to validate a self-signed hypervisor certificate against,
     * so verification is left off rather than failing every connection.
     *
     * Throws [IllegalArgumentException] when [type] is not
     * [VirtViewerType.SPICE] — a VNC descriptor must go down the RFB path.
     */
    fun toSpiceParams(): SpiceConnectionParams {
        require(type == VirtViewerType.SPICE) { "not a SPICE connection: $type" }
        return SpiceConnectionParams(
            host = host,
            port = port,
            tlsPort = tlsPort,
            password = password ?: "",
            caCert = caCert?.toByteArray(Charsets.US_ASCII),
            hostSubject = hostSubject,
            tlsVerify = caCert != null,
        )
    }

    /**
     * Redacted rendering. The compiler-generated data-class `toString` would
     * print the session ticket verbatim, and these objects flow through
     * error paths and log lines on the launch route.
     */
    override fun toString(): String =
        "VirtViewerConnection(type=$type, host=$host, port=$port, tlsPort=$tlsPort, " +
            "password=${if (password.isNullOrEmpty()) "<none>" else "xxxxx"}, " +
            "username=$username, " +
            "caCert=${caCert?.let { "${it.length} chars" } ?: "null"}, " +
            "hostSubject=$hostSubject, proxy=$proxy, title=$title, " +
            "deleteThisFile=$deleteThisFile, fullscreen=$fullscreen, " +
            "enableUsbredir=$enableUsbredir)"
}

/**
 * Raised when a `.vv` file or a `spice://` URI cannot be turned into a
 * [VirtViewerConnection]. The message names the offending key or rule and
 * is safe to show to the user — the parsers never quote a password value
 * into it.
 */
class VirtViewerParseException(message: String) : IllegalArgumentException(message)
