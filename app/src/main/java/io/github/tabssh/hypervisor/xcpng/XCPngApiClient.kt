package io.github.tabssh.hypervisor.xcpng
import io.github.tabssh.utils.logging.Logger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * XCP-ng/XenServer API Client
 * Uses XML-RPC based API
 * 
 * Supports both direct XCP-ng/XenServer connections and Xen Orchestra connections
 */
class XCPngApiClient(
    private val host: String,
    private val port: Int = 443,
    private val username: String,
    private val password: String,
    private val verifySsl: Boolean = false,
    private val pinnedCertSha256: String? = null,
    /**
     * Invoked synchronously, on the handshake thread, the instant
     * [capturedPin] receives a new SHA-256 — TOFU accept, silent
     * system-CA accept, or an explicit user ACCEPT_AND_PIN on a changed
     * cert. Callers use this to persist [getCapturedCertSha256] to the DB
     * right away instead of waiting for [authenticate] (or a later call
     * on this same shared client) to finish without throwing.
     */
    private val onPinCaptured: (() -> Unit)? = null
) {

    internal companion object {
        /**
         * Cap on a single XML-RPC response body. XAPI answers are a few KiB;
         * anything past 8 MiB is a broken or hostile endpoint.
         */
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        /**
         * Ensure an XAPI console URL carries the authenticated `session_id`.
         *
         * `console.get_location` returns a bare `https://host/console?ref=…`
         * with no credential; XAPI answers that with 401 unless the session is
         * supplied, so every console opened from a server-provided location
         * failed. Returns [url] untouched when it already carries a session or
         * when there is no session to add.
         */
        internal fun consoleUrlWithSession(url: String, sessionId: String?): String {
            if (sessionId.isNullOrBlank()) return url
            if (url.contains("session_id=")) return url
            val separator = if (url.contains('?')) "&" else "?"
            return "$url${separator}session_id=$sessionId"
        }
    }

    /** Instance-side shorthand for [consoleUrlWithSession] using the live session. */
    private fun withSessionId(url: String): String = consoleUrlWithSession(url, sessionId)

    private val baseUrl = "https://$host:$port"
    private val client: OkHttpClient

    // @Volatile: written by login() on whichever IO thread authenticated and
    // read by every later call, including console-URL construction running on
    // a different dispatcher thread.
    @Volatile private var sessionId: String? = null

    private val capturedPin = io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.CapturedPin()
    fun getCapturedCertSha256(): String? = capturedPin.sha256

    data class XenVM(
        val uuid: String,
        val name: String,
        val powerState: String,
        val memory: Long,
        val vcpus: Int,
        val isTemplate: Boolean,
        // IP from guest metrics
        val ipAddress: String? = null
    )

    data class XenHost(
        val uuid: String,
        val name: String,
        val hostname: String,
        val enabled: Boolean,
        val memoryTotal: Long,
        val memoryFree: Long
    )

    init {
        val builder = io.github.tabssh.network.SharedHttpClient.client.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.installTrust(
            builder, verifySsl, pinnedCertSha256, capturedPin, host, port,
            onPinCaptured = onPinCaptured
        )
        client = builder.build()
    }

    /** Cancel any in-flight HTTP calls. Safe to call from Activity.onDestroy(). */
    fun cancelAll() {
        try { client.dispatcher.cancelAll() } catch (e: Exception) { Logger.w("XCPngAPI", "cancelAll: ${e.message}") }
    }

    suspend fun authenticate(): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d("XCPngAPI", "Attempting authentication to $baseUrl")
            
            val xmlRequest = """
                <?xml version="1.0"?>
                <methodCall>
                    <methodName>session.login_with_password</methodName>
                    <params>
                        <param><value>${xmlEscape(username)}</value></param>
                        <param><value>${xmlEscape(password)}</value></param>
                    </params>
                </methodCall>
            """.trimIndent()

            val response = xmlRpcCall(xmlRequest)

            Logger.d("XCPngAPI", "Auth response length: ${response.length}")

            // Detect HTML response (likely Xen Orchestra web interface)
            if (response.trim().startsWith("<!DOCTYPE") || response.trim().startsWith("<html")) {
                Logger.e("XCPngAPI", "Received HTML instead of XML-RPC - this is likely a Xen Orchestra instance")
                Logger.d("XCPngAPI", "Response: ${response.take(200)}")
                throw IllegalStateException("Cannot connect to XCP-ng API - server returned HTML (Xen Orchestra web interface). Please either:\n1. Enable 'Is this Xen Orchestra?' toggle in hypervisor settings\n2. Connect directly to XCP-ng host (not Xen Orchestra) on default port")
            }

            // XAPI wraps every result in a {Status, Value|ErrorDescription} struct;
            // xapiValue unwraps it and throws on Status=Failure or an XML-RPC fault.
            val session = xapiValue(response)
            if (session is String && session.startsWith("OpaqueRef:")) {
                sessionId = session
                // The session ref IS the credential for every later XAPI call —
                // never log any part of it.
                Logger.i("XCPngAPI", "Authentication successful, session: xxxxx")
                true
            } else {
                Logger.e("XCPngAPI", "Authentication failed - unexpected response format")
                Logger.d("XCPngAPI", "Response: ${response.take(500)}")
                false
            }
        } catch (e: IOException) {
            Logger.e("XCPngAPI", "Network error during authentication: ${e.message}", e)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Authentication error: ${e.message}", e)
            false
        }
    }

    suspend fun getAllVMs(): List<XenVM> = withContext(Dispatchers.IO) {
        try {
            // Get all VM references
            val vmsXml = xmlRpcCall(buildXmlRequest("VM.get_all", emptyList()))
            val vmRefs = parseArrayResponse(vmsXml)
            
            val vms = mutableListOf<XenVM>()
            
            // Get details for each VM
            vmRefs.forEach { vmRef ->
                val nameXml = xmlRpcCall(buildXmlRequest("VM.get_name_label", listOf(vmRef)))
                val powerXml = xmlRpcCall(buildXmlRequest("VM.get_power_state", listOf(vmRef)))
                val memoryXml = xmlRpcCall(buildXmlRequest("VM.get_memory_dynamic_max", listOf(vmRef)))
                val vcpusXml = xmlRpcCall(buildXmlRequest("VM.get_VCPUs_max", listOf(vmRef)))
                val templateXml = xmlRpcCall(buildXmlRequest("VM.get_is_a_template", listOf(vmRef)))
                
                val name = parseStringResponse(nameXml)
                val powerState = parseStringResponse(powerXml)
                val memory = parseIntResponse(memoryXml)
                val vcpus = parseIntResponse(vcpusXml).toInt()
                val isTemplate = parseBooleanResponse(templateXml)
                
                if (!isTemplate){
                    vms.add(XenVM(vmRef, name, powerState, memory, vcpus, isTemplate))
                }
            }
            
            Logger.d("XCPngAPI", "Retrieved ${vms.size} VMs")
            vms
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to get VMs", e)
            emptyList()
        }
    }

    suspend fun startVM(uuid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // VM.start(start_paused, force) take XML-RPC booleans, not strings;
            // xapiValue throws on Status=Failure so a rejected start returns false.
            xapiValue(xmlRpcCall(buildXmlRequest("VM.start", listOf(uuid, false, false))))
            Logger.i("XCPngAPI", "Started VM $uuid")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to start VM", e)
            false
        }
    }

    suspend fun shutdownVM(uuid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            xapiValue(xmlRpcCall(buildXmlRequest("VM.clean_shutdown", listOf(uuid))))
            Logger.i("XCPngAPI", "Shutdown VM $uuid")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to shutdown VM", e)
            false
        }
    }

    suspend fun rebootVM(uuid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            xapiValue(xmlRpcCall(buildXmlRequest("VM.clean_reboot", listOf(uuid))))
            Logger.i("XCPngAPI", "Rebooted VM $uuid")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to reboot VM", e)
            false
        }
    }

    suspend fun hardShutdownVM(uuid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            xapiValue(xmlRpcCall(buildXmlRequest("VM.hard_shutdown", listOf(uuid))))
            Logger.i("XCPngAPI", "Hard shutdown VM $uuid")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to hard shutdown VM", e)
            false
        }
    }

    /**
     * Hard reboot a VM (immediate power cycle, like pressing reset button)
     * Unlike clean_reboot, this does not wait for graceful shutdown.
     */
    suspend fun hardRebootVM(uuid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            xapiValue(xmlRpcCall(buildXmlRequest("VM.hard_reboot", listOf(uuid))))
            Logger.i("XCPngAPI", "Hard reboot VM $uuid")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to hard reboot VM", e)
            false
        }
    }

    private fun buildXmlRequest(method: String, params: List<Any>): String {
        val paramsXml = params.joinToString("") { param ->
            // XML-RPC booleans are typed elements encoding 1/0 — a bare "false"
            // string is truthy to the server-side boolean coercion.
            when (param) {
                is Boolean -> "<param><value><boolean>${if (param) "1" else "0"}</boolean></value></param>"
                else       -> "<param><value>${xmlEscape(param.toString())}</value></param>"
            }
        }

        return """
            <?xml version="1.0"?>
            <methodCall>
                <methodName>${xmlEscape(method)}</methodName>
                <params>
                    <param><value>${xmlEscape(sessionId.orEmpty())}</value></param>
                    $paramsXml
                </params>
            </methodCall>
        """.trimIndent()
    }

    // XML-RPC values must be escaped: a bare '&', '<', or '>' inside a value
    // breaks the parser server-side (XML well-formedness violation) and an
    // attacker-controlled password containing "</value><value>OpaqueRef:..."
    // could otherwise smuggle a forged session id past the auth call. Apply
    // to every user-supplied or response-derived string interpolated into
    // request bodies.
    private fun xmlEscape(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '&'  -> sb.append("&amp;")
                '<'  -> sb.append("&lt;")
                '>'  -> sb.append("&gt;")
                '"'  -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun xmlRpcCall(xml: String): String {
        val requestBody = xml.toRequestBody("text/xml".toMediaType())
        
        val request = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .header("Content-Type", "text/xml")
            .build()

        try {
            return client.newCall(request).execute().use { response ->
                // Bounded read: a hostile or broken endpoint can answer an
                // XML-RPC POST with an endless body, and string() would buffer
                // all of it into the heap before we ever look at it.
                val responseBody = response.body?.source()?.let { source ->
                    source.request(MAX_RESPONSE_BYTES + 1L)
                    val truncated = source.buffer.size > MAX_RESPONSE_BYTES
                    if (truncated) {
                        Logger.w("XCPngAPI", "XML-RPC response exceeded ${MAX_RESPONSE_BYTES} bytes — rejecting")
                        throw IOException("XML-RPC response too large")
                    }
                    source.readByteString().utf8()
                }
                if (response.isSuccessful && responseBody != null) {
                    responseBody
                } else {
                    val errorMsg = "XML-RPC call failed: ${response.code} ${response.message}"
                    Logger.e("XCPngAPI", "$errorMsg - Body: ${responseBody?.take(200)}")
                    throw IOException(errorMsg)
                }
            }
        } catch (e: IOException) {
            Logger.e("XCPngAPI", "Network error during XML-RPC call: ${e.message}")
            throw e
        }
    }

    /**
     * Parse an XML-RPC methodResponse and unwrap the XAPI result envelope.
     *
     * XAPI wraps every response in `<value><struct>` with members `Status`
     * ("Success"/"Failure"), `Value` (present on success) and
     * `ErrorDescription` (array of strings on failure). Throws [IOException]
     * on an XML-RPC `<fault>`, on Status=Failure (message carries the joined
     * ErrorDescription), or on malformed/truncated XML. Returns the `Value`
     * member as String / Boolean / List / Map, or "" for void results.
     */
    private fun xapiValue(xml: String): Any {
        val root = parseMethodResponse(xml)
        if (root !is Map<*, *>) {
            throw IOException("Unexpected XML-RPC response shape: ${root.javaClass.simpleName}")
        }
        val status = root["Status"]
        if (status != "Success") {
            val error = (root["ErrorDescription"] as? List<*>)
                ?.joinToString(" ") { it.toString() }
                ?: "unknown error"
            throw IOException("XAPI failure: $error")
        }
        return root["Value"] ?: ""
    }

    // Returns the value of the first <param> in a methodResponse, throwing on
    // a <fault> element (transport-level XML-RPC error, distinct from the
    // XAPI Status=Failure envelope handled by xapiValue).
    private fun parseMethodResponse(xml: String): Any {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        var inFault = false
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "fault" -> inFault = true
                    "value" -> {
                        val value = parseXmlRpcValue(parser)
                        if (inFault) {
                            val fault = value as? Map<*, *>
                            throw IOException("XML-RPC fault: ${fault?.get("faultString") ?: value}")
                        }
                        return value
                    }
                }
            }
            event = parser.next()
        }
        throw IOException("XML-RPC response contains no value")
    }

    // Parses the content of a <value> element the parser is currently on.
    // Handles bare text, <string>, <boolean>, numeric scalars (returned as
    // String — callers convert), <array><data> and <struct>.
    private fun parseXmlRpcValue(parser: XmlPullParser): Any {
        val text = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "string", "int", "i4", "i8", "double", "dateTime.iso8601", "base64" -> {
                            val scalar = readElementText(parser)
                            skipToValueEnd(parser)
                            return scalar
                        }
                        "boolean" -> {
                            val raw = readElementText(parser).trim()
                            skipToValueEnd(parser)
                            return raw == "1" || raw.equals("true", ignoreCase = true)
                        }
                        "array" -> {
                            val items = parseXmlRpcArray(parser)
                            skipToValueEnd(parser)
                            return items
                        }
                        "struct" -> {
                            val members = parseXmlRpcStruct(parser)
                            skipToValueEnd(parser)
                            return members
                        }
                        else -> throw IOException("Unsupported XML-RPC type: ${parser.name}")
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "value") return text.toString()
                }
                XmlPullParser.END_DOCUMENT -> throw IOException("Truncated XML-RPC value")
            }
        }
    }

    // Consumes events until the enclosing </value> so parseXmlRpcValue always
    // leaves the parser positioned right after the value it returned.
    private fun skipToValueEnd(parser: XmlPullParser) {
        var depth = 0
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> {
                    if (depth == 0 && parser.name == "value") return
                    depth--
                }
                XmlPullParser.END_DOCUMENT -> throw IOException("Truncated XML-RPC value")
            }
        }
    }

    private fun parseXmlRpcArray(parser: XmlPullParser): List<Any> {
        val items = mutableListOf<Any>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "value") items.add(parseXmlRpcValue(parser))
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "array") return items
                }
                XmlPullParser.END_DOCUMENT -> throw IOException("Truncated XML-RPC array")
            }
        }
    }

    private fun parseXmlRpcStruct(parser: XmlPullParser): Map<String, Any> {
        val members = mutableMapOf<String, Any>()
        var name: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "name" -> name = readElementText(parser)
                    "value" -> {
                        val value = parseXmlRpcValue(parser)
                        if (name != null) {
                            members[name] = value
                            name = null
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "struct") return members
                }
                XmlPullParser.END_DOCUMENT -> throw IOException("Truncated XML-RPC struct")
            }
        }
    }

    // Reads the text content of the element the parser just opened and
    // consumes its END_TAG. The pull parser decodes XML entities itself.
    private fun readElementText(parser: XmlPullParser): String {
        val text = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> return text.toString()
                XmlPullParser.START_TAG -> throw IOException("Unexpected element in scalar: ${parser.name}")
                XmlPullParser.END_DOCUMENT -> throw IOException("Truncated XML-RPC element")
            }
        }
    }

    private fun parseStringResponse(xml: String): String {
        return xapiValue(xml).toString()
    }

    private fun parseIntResponse(xml: String): Long {
        return parseStringResponse(xml).toLongOrNull() ?: 0L
    }

    private fun parseBooleanResponse(xml: String): Boolean {
        return when (val value = xapiValue(xml)) {
            is Boolean -> value
            else -> value.toString() == "true" || value.toString() == "1"
        }
    }

    private fun parseArrayResponse(xml: String): List<String> {
        return (xapiValue(xml) as? List<*>)?.map { it.toString() } ?: emptyList()
    }

    /**
     * Get console URL for VM.
     * Uses XenAPI to get console reference and location.
     *
     * @param vmRef VM reference (OpaqueRef:...)
     * @return WebSocket URL for console, or null on failure
     */
    suspend fun getConsoleUrl(vmRef: String): String? = withContext(Dispatchers.IO) {
        try {
            Logger.d("XCPngAPI", "Getting console for VM: $vmRef")

            // Get console references for this VM
            val consolesResponse = xmlRpcCall(buildXmlRequest("VM.get_consoles", listOf(vmRef)))
            val consoleRefs = parseArrayResponse(consolesResponse)

            if (consoleRefs.isEmpty()) {
                Logger.w("XCPngAPI", "No consoles found for VM")
                return@withContext null
            }

            // Get the first console (typically VNC or text console)
            val consoleRef = consoleRefs.firstOrNull { it.startsWith("OpaqueRef:") }
            if (consoleRef == null) {
                Logger.w("XCPngAPI", "No valid console reference found")
                return@withContext null
            }

            Logger.d("XCPngAPI", "Found console ref: $consoleRef")

            // Get console location (URL)
            val locationResponse = xmlRpcCall(buildXmlRequest("console.get_location", listOf(consoleRef)))
            val location = parseStringResponse(locationResponse)

            if (location.isNotEmpty()) {
                // The location might be HTTP, convert to WebSocket if needed
                val wsUrl = if (location.startsWith("http://")) {
                    withSessionId(location.replace("http://", "ws://"))
                } else if (location.startsWith("https://")) {
                    withSessionId(location.replace("https://", "wss://"))
                } else {
                    // Assume it's already a proper URL, construct WebSocket URL
                    "wss://$host/console?ref=$consoleRef&session_id=$sessionId"
                }

                // Log scheme+host+path only — the query string carries the
                // live session_id credential.
                Logger.i("XCPngAPI", "Console URL: ${Logger.urlForLogging(wsUrl)}")
                wsUrl
            } else {
                // Fallback: construct console URL manually
                val fallbackUrl = "wss://$host/console?ref=$consoleRef&session_id=$sessionId"
                Logger.i("XCPngAPI", "Using fallback console URL: ${Logger.urlForLogging(fallbackUrl)}")
                fallbackUrl
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to get console URL", e)
            null
        }
    }

    /**
     * Resolved XCP-ng console handle: the wire URL plus the protocol the
     * remote endpoint speaks once the WebSocket is open.
     *
     * XAPI consoles advertise one of `"rfb"` (graphical, RFB / VNC),
     * `"vt100"` (text serial), or `"rdp"` (Windows guests with the XenServer
     * tools installed).  `"rfb"` and `"vt100"` are the only ones this client
     * currently consumes.
     */
    data class ConsoleInfo(val url: String, val protocol: String)

    /**
     * Find the first console on [vmRef] whose XAPI `protocol` field matches
     * [protocol] (case-insensitive) and return its WebSocket URL.  Returns
     * null when the VM exposes no console of that protocol — let the caller
     * decide whether to fall back to a different protocol.
     *
     * Used by [io.github.tabssh.hypervisor.console.HypervisorConsoleManager]
     * to assemble an "rfb first, vt100 second" strategy chain: VMs configured
     * for graphical display get the VNC path automatically, headless / serial
     * VMs fall through to the text console without any user-visible toast.
     */
    suspend fun getConsoleByProtocol(vmRef: String, protocol: String): ConsoleInfo? = withContext(Dispatchers.IO) {
        try {
            val consolesResponse = xmlRpcCall(buildXmlRequest("VM.get_consoles", listOf(vmRef)))
            val consoleRefs = parseArrayResponse(consolesResponse)
                .filter { it.startsWith("OpaqueRef:") }
            for (ref in consoleRefs) {
                val proto = parseStringResponse(
                    xmlRpcCall(buildXmlRequest("console.get_protocol", listOf(ref)))
                )
                if (!proto.equals(protocol, ignoreCase = true)) continue
                val location = parseStringResponse(
                    xmlRpcCall(buildXmlRequest("console.get_location", listOf(ref)))
                )
                val url = when {
                    location.startsWith("http://")  -> withSessionId(location.replace("http://",  "ws://"))
                    location.startsWith("https://") -> withSessionId(location.replace("https://", "wss://"))
                    location.isNotEmpty()           -> withSessionId(location)
                    else                            -> "wss://$host/console?ref=$ref&session_id=$sessionId"
                }
                // Log scheme+host+path only — the query string carries the
                // live session_id credential.
                Logger.i("XCPngAPI", "Resolved $proto console for VM: ${Logger.urlForLogging(url)}")
                return@withContext ConsoleInfo(url, proto.lowercase())
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("XCPngAPI", "getConsoleByProtocol($protocol) failed: ${e.message}")
            null
        }
    }

    /**
     * Get VM reference by UUID
     */
    suspend fun getVMRefByUUID(uuid: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = xmlRpcCall(buildXmlRequest("VM.get_by_uuid", listOf(uuid)))
            val ref = parseStringResponse(response)
            if (ref.startsWith("OpaqueRef:")) ref else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to get VM ref for UUID $uuid", e)
            null
        }
    }
}
