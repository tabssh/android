package io.github.tabssh.hypervisor.viewer

/**
 * Parser for virt-viewer `.vv` connection files.
 *
 * A `.vv` file is a GKeyFile-style INI document whose `[virt-viewer]`
 * section describes a single display session. oVirt, RHV, Proxmox and
 * `virt-manager` all hand one of these to the browser, which in turn hands
 * it to whatever app claims `application/x-virt-viewer`.
 *
 * Example:
 * ```ini
 * [virt-viewer]
 * type=spice
 * host=192.0.2.10
 * port=5900
 * tls-port=5901
 * password=s3cret
 * host-subject=O=Example,CN=host.example.org
 * ca=-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----\n
 * delete-this-file=1
 * ```
 *
 * ## Trust model
 *
 * The file arrives from outside the app and is treated as hostile input.
 * Every bound below exists so a malicious or corrupt file cannot exhaust
 * memory, smuggle a control character into a host name, or steer the
 * client at a port outside the legal range:
 *
 * - total document length, line count, and per-value length are capped
 * - `type` must be exactly `spice` or `vnc`
 * - `host` must be non-blank, within [MAX_HOST_LEN], and free of
 *   whitespace and control characters
 * - `port` and `tls-port` must parse as integers in 1..65535, and at
 *   least one of them must be present
 * - unknown keys and unknown sections are ignored, matching virt-viewer
 *
 * This object is deliberately free of Android framework types so it can be
 * exercised by plain JVM unit tests.
 */
object VirtViewerFile {

    /** Section every recognised key must live under. */
    private const val SECTION = "virt-viewer"

    /** Largest `.vv` document accepted, in characters. */
    const val MAX_CONTENT_LEN = 256 * 1024

    /** Largest number of lines accepted. */
    const val MAX_LINES = 4096

    /** Largest accepted value for any key other than `ca`. */
    const val MAX_VALUE_LEN = 4096

    /** Largest accepted `ca` value — a PEM chain, so roomier. */
    const val MAX_CA_LEN = 64 * 1024

    /** Largest accepted host name. */
    const val MAX_HOST_LEN = 255

    /**
     * Parse `content` into a [VirtViewerConnection].
     *
     * @throws VirtViewerParseException when the document is not a usable
     *   virt-viewer connection file. The message is safe to display and
     *   never contains a password value.
     */
    @JvmStatic
    fun parse(content: String): VirtViewerConnection {
        if (content.length > MAX_CONTENT_LEN) {
            throw VirtViewerParseException("connection file too large")
        }

        val keys = readSection(content)
        if (keys.isEmpty()) {
            throw VirtViewerParseException("no [$SECTION] section")
        }

        val type = when (val raw = keys["type"]?.lowercase()) {
            "spice" -> VirtViewerType.SPICE
            "vnc" -> VirtViewerType.VNC
            null -> throw VirtViewerParseException("missing 'type'")
            else -> throw VirtViewerParseException("unsupported type '${sanitize(raw)}'")
        }

        val host = keys["host"]?.trim().orEmpty()
        if (host.isEmpty()) throw VirtViewerParseException("missing 'host'")
        if (host.length > MAX_HOST_LEN) throw VirtViewerParseException("'host' too long")
        if (!isPlausibleHost(host)) throw VirtViewerParseException("invalid 'host'")

        val port = port(keys, "port")
        val tlsPort = port(keys, "tls-port")
        if (port == 0 && tlsPort == 0) {
            throw VirtViewerParseException("neither 'port' nor 'tls-port' given")
        }

        val ca = keys["ca"]?.takeIf { it.isNotBlank() }?.let { unescape(it) }

        return VirtViewerConnection(
            type = type,
            host = host,
            port = port,
            tlsPort = tlsPort,
            password = keys["password"]?.takeIf { it.isNotEmpty() },
            caCert = ca,
            hostSubject = keys["host-subject"]?.takeIf { it.isNotBlank() },
            proxy = keys["proxy"]?.takeIf { it.isNotBlank() },
            title = keys["title"]?.takeIf { it.isNotBlank() },
            deleteThisFile = bool(keys["delete-this-file"]),
            fullscreen = bool(keys["fullscreen"]),
            enableUsbredir = bool(keys["enable-usbredir"]),
            releaseCursor = keys["release-cursor"]?.takeIf { it.isNotBlank() },
            secureAttention = keys["secure-attention"]?.takeIf { it.isNotBlank() },
            toggleFullscreen = keys["toggle-fullscreen"]?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Parse `content`, returning null instead of throwing. Convenience for
     * call sites that only need to know whether the payload was a usable
     * connection file.
     */
    @JvmStatic
    fun parseOrNull(content: String): VirtViewerConnection? =
        try {
            parse(content)
        } catch (e: VirtViewerParseException) {
            null
        }

    /**
     * Cheap sniff for whether a payload looks like a `.vv` file, used to
     * decide whether to attempt a full parse on a generic-MIME intent.
     * Only inspects the leading window so a large unrelated file is not
     * scanned end to end.
     */
    @JvmStatic
    fun looksLikeVirtViewerFile(content: String): Boolean =
        content.take(4096).contains("[$SECTION]", ignoreCase = true)

    /**
     * Collect the `key=value` pairs of the `[virt-viewer]` section.
     *
     * Keys are lowercased so a file written with `Type=` still resolves;
     * values keep their case and interior spacing but are trimmed at both
     * ends, matching how virt-viewer's GKeyFile reader behaves. A repeated
     * key takes its last value.
     */
    private fun readSection(content: String): Map<String, String> {
        val out = HashMap<String, String>()
        var inSection = false
        var lines = 0

        for (rawLine in content.lineSequence()) {
            if (++lines > MAX_LINES) {
                throw VirtViewerParseException("connection file has too many lines")
            }

            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue

            if (line.startsWith("[")) {
                val close = line.indexOf(']')
                if (close < 0) throw VirtViewerParseException("malformed section header")
                inSection = line.substring(1, close).trim().equals(SECTION, ignoreCase = true)
                continue
            }

            if (!inSection) continue

            val eq = line.indexOf('=')
            if (eq <= 0) continue

            val key = line.substring(0, eq).trim().lowercase()
            if (key.isEmpty()) continue

            val value = line.substring(eq + 1).trim()
            val limit = if (key == "ca") MAX_CA_LEN else MAX_VALUE_LEN
            if (value.length > limit) {
                throw VirtViewerParseException("value for '$key' too long")
            }
            out[key] = value
        }

        return out
    }

    /**
     * Read a port key. Absent or empty yields 0 ("not offered"); anything
     * present must be a legal TCP port. `0` is rejected rather than
     * silently meaning "absent" — a file that explicitly says port 0 is
     * malformed, not a file without a port.
     */
    private fun port(keys: Map<String, String>, name: String): Int {
        val raw = keys[name]?.trim()
        if (raw.isNullOrEmpty()) return 0
        val value = raw.toIntOrNull()
            ?: throw VirtViewerParseException("'$name' is not a number")
        if (value !in 1..65535) throw VirtViewerParseException("'$name' out of range")
        return value
    }

    /**
     * GKeyFile boolean. virt-viewer writes `1`/`0`; `true`/`false`/`yes`/`on`
     * are accepted too because hypervisors are not consistent about it.
     * Anything unrecognised is false rather than an error — an unusable
     * cosmetic flag must not sink an otherwise valid connection.
     */
    private fun bool(value: String?): Boolean =
        when (value?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            else -> false
        }

    /**
     * Turn the escape sequences virt-viewer writes into a single-line value
     * back into real characters. Only the sequences GKeyFile emits are
     * recognised; a trailing lone backslash is kept verbatim.
     */
    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i == value.length - 1) {
                sb.append(c)
                i++
                continue
            }
            when (val next = value[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                '\\' -> sb.append('\\')
                else -> {
                    sb.append(c)
                    sb.append(next)
                }
            }
            i += 2
        }
        return sb.toString()
    }

    /**
     * Reject host values that could not be a host name or IP literal:
     * anything containing whitespace, a control character, or a character
     * that would let the value break out of the field it is later used in
     * (URI building, TLS subject comparison, log lines).
     */
    private fun isPlausibleHost(host: String): Boolean =
        host.none {
            it.isWhitespace() || it.isISOControl() ||
                it == '/' || it == '\\' || it == '@' || it == '"'
        }

    /**
     * Strip control characters out of a value before it is quoted into an
     * error message shown to the user.
     */
    private fun sanitize(value: String): String =
        value.take(32).filter { !it.isISOControl() }
}
