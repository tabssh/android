package io.github.tabssh.hypervisor.viewer

import io.github.tabssh.utils.logging.Logger
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Best-effort parser for JNLP (Java Network Launch Protocol) files handed
 * out by out-of-band management console web UIs — HP iLO, Dell iDRAC,
 * Supermicro/AMI MegaRAC and similar BMC "Launch Console" buttons.
 *
 * A `.jnlp` file only ever describes how to *launch a desktop Java Web
 * Start applet* — it never says anything about the wire protocol that
 * applet speaks once it opens a socket, and TabSSH has no JVM to run that
 * applet in. What this parser extracts instead is the handful of fields
 * every vendor's launcher.jnlp reliably carries in a form a generic RFB
 * client can use directly: the BMC's own address (the `codebase`/`href`
 * attribute — always present, part of the JNLP spec itself) and, among the
 * `<argument>` list passed to the applet's main class, whichever entries
 * look like a TCP port and a session ticket.
 *
 * There is no vendor allowlist: some BMCs (Supermicro/AMI MegaRAC iKVM,
 * newer iDRAC9 with VNC mode enabled) really do run standard RFB behind
 * this launcher and a [io.github.tabssh.hypervisor.console.rfb.RfbClient]
 * connects to them exactly like any other [io.github.tabssh.storage.database.entities.VncHost].
 * Others (classic HP iLO Integrated Remote Console, older Dell avctKVM) run
 * a proprietary protocol no RFB client can speak — for those, the parse
 * still succeeds (the fields are still there to extract) but the RFB
 * handshake itself fails once a connection is attempted, surfacing as a
 * normal connection error in the console tab rather than a parse error
 * here. This parser deliberately does not try to tell the two cases apart
 * up front; [LinkHandlerActivity] always lets the user review/edit the
 * extracted host and port before connecting, which covers a wrong heuristic
 * guess the same way a bad hand-typed VNC host is covered today.
 *
 * ## Trust model
 *
 * Identical posture to [VirtViewerFile]: the file arrives from outside the
 * app and is treated as hostile input.
 * - total document length and argument count/length are capped
 * - the XML parser has DOCTYPE processing disabled outright (no external
 *   entity or external DTD can be fetched — this document is never allowed
 *   to make TabSSH issue a network request of the parser's choosing)
 * - the extracted host is validated with the same "no whitespace, no
 *   control characters, no separator" rule [VirtViewerFile] uses
 * - a candidate session ticket is treated exactly like a `.vv` password:
 *   used once for the RFB handshake, never logged, never persisted unless
 *   the user explicitly saves the resulting host
 *
 * Framework-free (`javax.xml`/`org.w3c.dom` only, both plain JDK), so this
 * is exercised by plain JVM unit tests.
 */
object JnlpFile {

    private const val TAG = "JnlpFile"

    /** Largest `.jnlp` document accepted, in characters. */
    const val MAX_CONTENT_LEN = 256 * 1024

    /** Largest number of `<argument>` elements inspected. */
    const val MAX_ARGUMENTS = 128

    /** Largest accepted length for any single `<argument>` value. */
    const val MAX_ARG_LEN = 4096

    /** Largest accepted host name. */
    const val MAX_HOST_LEN = 255

    /** Port used when no plausible port can be found among the arguments. */
    const val DEFAULT_PORT = 5900

    /** Smallest argument length treated as a candidate session ticket. */
    private const val MIN_TICKET_LEN = 16

    /**
     * Parse `content` into a [JnlpConnection].
     *
     * @throws JnlpParseException when the document is not a usable JNLP
     *   console launcher. The message is safe to display and never
     *   contains a ticket value.
     */
    @JvmStatic
    fun parse(content: String): JnlpConnection {
        if (content.length > MAX_CONTENT_LEN) {
            throw JnlpParseException("connection file too large")
        }
        if (!content.contains("<jnlp", ignoreCase = true)) {
            throw JnlpParseException("not a JNLP file")
        }

        val root = parseDocument(content)

        val codebase = root.getAttribute("codebase").trim().takeIf { it.isNotEmpty() }
        val href = root.getAttribute("href").trim().takeIf { it.isNotEmpty() }
        val codebaseHost = (codebase ?: href)?.let { hostFromUrl(it) }

        val title = firstElementText(root, "information", "title")

        val arguments = collectArguments(root)

        val host = codebaseHost
            ?: arguments.firstOrNull { isPlausibleHost(it) && !it.all { c -> c.isDigit() } }
            ?: throw JnlpParseException("no usable host found")
        if (host.length > MAX_HOST_LEN) throw JnlpParseException("host too long")
        if (!isPlausibleHost(host)) throw JnlpParseException("invalid host")

        val port = arguments
            .mapNotNull { it.toIntOrNull() }
            .firstOrNull { it in 1..65535 && (it >= 1024 || it in 5900..5999) }
            ?: DEFAULT_PORT

        val ticket = arguments
            .filter { it.length >= MIN_TICKET_LEN && it.all { c -> c.isLetterOrDigit() } }
            .maxByOrNull { it.length }

        return JnlpConnection(
            host = host,
            port = port,
            ticket = ticket,
            title = title
        )
    }

    /**
     * Parse `content`, returning null instead of throwing. Convenience for
     * call sites that only need to know whether the payload was usable.
     */
    @JvmStatic
    fun parseOrNull(content: String): JnlpConnection? =
        try {
            parse(content)
        } catch (e: JnlpParseException) {
            null
        }

    /**
     * Cheap sniff for whether a payload looks like a `.jnlp` file, used to
     * decide whether to attempt a full parse on a generic-MIME intent. Only
     * inspects the leading window so a large unrelated file is not scanned
     * end to end.
     */
    @JvmStatic
    fun looksLikeJnlpFile(content: String): Boolean =
        content.take(4096).contains("<jnlp", ignoreCase = true)

    /**
     * Build a DOM [Element] for the `<jnlp>` root, with every entity/DTD
     * feature that could make the parser reach outside this document
     * turned off.
     *
     * `disallow-doctype-decl=true` is the strongest available guard against
     * an XXE/billion-laughs payload and is tried first, but this is an
     * Apache/Xerces-specific feature URI that not every device's built-in
     * `DocumentBuilderFactory` implementation honours — some throw
     * [ParserConfigurationException] just for asking. When that happens this
     * falls back to a manual literal `<!DOCTYPE` rejection on the raw text
     * below, which is a weaker but still effective guard (any doctype
     * declaration is refused outright, so no external entity/DTD can be
     * referenced) rather than failing every `.jnlp` file on that device.
     */
    private fun parseDocument(content: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        val doctypeDeclDisallowed = try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            true
        } catch (e: ParserConfigurationException) {
            Logger.w(TAG, "disallow-doctype-decl unsupported by platform XML parser, falling back to manual check", e)
            false
        }
        if (!doctypeDeclDisallowed && content.contains("<!DOCTYPE", ignoreCase = true)) {
            throw JnlpParseException("DOCTYPE declarations are not allowed")
        }
        factory.isExpandEntityReferences = false
        factory.isXIncludeAware = false
        factory.isNamespaceAware = false

        val document = try {
            factory.newDocumentBuilder().parse(
                org.xml.sax.InputSource(StringReader(content))
            )
        } catch (e: Exception) {
            throw JnlpParseException("malformed XML: ${e.message?.take(120)}", e)
        }

        val root = document.documentElement
            ?: throw JnlpParseException("empty document")
        if (!root.tagName.equals("jnlp", ignoreCase = true)) {
            throw JnlpParseException("root element is not <jnlp>")
        }
        return root
    }

    /**
     * Collect the text of every `<argument>` element under `<application-desc>`
     * or `<applet-desc>`, in document order, bounded by [MAX_ARGUMENTS] and
     * [MAX_ARG_LEN].
     */
    private fun collectArguments(root: Element): List<String> {
        val descTags = listOf("application-desc", "applet-desc")
        val out = ArrayList<String>()

        for (descTag in descTags) {
            val descNodes = root.getElementsByTagName(descTag)
            for (i in 0 until descNodes.length) {
                val desc = descNodes.item(i) as? Element ?: continue
                val argNodes = desc.getElementsByTagName("argument")
                for (j in 0 until argNodes.length) {
                    if (out.size >= MAX_ARGUMENTS) return out
                    val text = argNodes.item(j).textContent?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    if (text.length > MAX_ARG_LEN) {
                        throw JnlpParseException("argument value too long")
                    }
                    out.add(text)
                }
            }
        }
        return out
    }

    /**
     * Text of the first `<parentTag><childTag>...</childTag></parentTag>`
     * match anywhere under `root`, or null.
     */
    private fun firstElementText(root: Element, parentTag: String, childTag: String): String? {
        val parents = root.getElementsByTagName(parentTag)
        for (i in 0 until parents.length) {
            val parent = parents.item(i) as? Element ?: continue
            val children = parent.getElementsByTagName(childTag)
            if (children.length > 0) {
                val text = children.item(0).textContent?.trim()
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }

    /**
     * Extract the host from a `codebase`/`href` URL such as
     * `https://192.0.2.10:443/` — the address the BMC's own web UI is
     * running on, which is also where its console service listens.
     */
    private fun hostFromUrl(url: String): String? =
        try {
            java.net.URI(url).host?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

    /**
     * Reject host values that could not be a host name or IP literal — same
     * rule [VirtViewerFile] applies.
     */
    private fun isPlausibleHost(host: String): Boolean =
        host.isNotBlank() && host.none {
            it.isWhitespace() || it.isISOControl() ||
                it == '/' || it == '\\' || it == '@' || it == '"'
        }
}

/**
 * A parsed JNLP console launcher — the best-effort connection info
 * [JnlpFile] could extract from it.
 *
 * @property host BMC/console host, from the JNLP `codebase`/`href` when
 *   present, otherwise the first plausible-looking non-numeric argument.
 * @property port Candidate RFB port, defaulting to [JnlpFile.DEFAULT_PORT]
 *   when no argument looks like one.
 * @property ticket Candidate session ticket to try as the RFB/VNC password,
 *   or null when no argument looked like one. Never logged — see
 *   [toString].
 * @property title Human-readable session title from `<information><title>`,
 *   or null.
 */
data class JnlpConnection(
    val host: String,
    val port: Int,
    val ticket: String?,
    val title: String?
) {
    /** Redacted rendering — see [VirtViewerConnection.toString] for why. */
    override fun toString(): String =
        "JnlpConnection(host=$host, port=$port, " +
            "ticket=${if (ticket.isNullOrEmpty()) "<none>" else "xxxxx"}, title=$title)"
}

/**
 * Raised when a `.jnlp` file cannot be turned into a [JnlpConnection]. The
 * message names the offending rule and is safe to display — the parser
 * never quotes a ticket value into it. [cause], when present, carries the
 * real underlying platform exception (e.g. a SAX parse failure) purely for
 * diagnostics — it is never shown to the user, only logged.
 */
class JnlpParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
