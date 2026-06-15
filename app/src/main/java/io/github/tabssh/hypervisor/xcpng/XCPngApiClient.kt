package io.github.tabssh.hypervisor.xcpng
import io.github.tabssh.utils.logging.Logger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
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
    private val pinnedCertSha256: String? = null
) {

    private val baseUrl = "https://$host:$port"
    private val client: OkHttpClient
    private var sessionId: String? = null

    private val capturedPin = io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.CapturedPin()
    fun getCapturedCertSha256(): String? = capturedPin.sha256

    data class XenVM(
        val uuid: String,
        val name: String,
        val powerState: String,
        val memory: Long,
        val vcpus: Int,
        val isTemplate: Boolean,
        val ipAddress: String? = null // IP from guest metrics
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
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.installTrust(
            builder, verifySsl, pinnedCertSha256, capturedPin, host, port
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

            // Parse XML response for session ID
            if (response.contains("<value>") && response.contains("OpaqueRef:")) {
                val start = response.indexOf("<value>") + 7
                val end = response.indexOf("</value>", start)
                sessionId = response.substring(start, end)
                Logger.i("XCPngAPI", "Authentication successful, session: ${sessionId?.take(20)}...")
                true
            } else if (response.contains("Fault") || response.contains("fault")) {
                // Parse error message
                val errorStart = response.indexOf("<string>")
                val errorEnd = response.indexOf("</string>", errorStart)
                val errorMsg = if (errorStart > 0 && errorEnd > errorStart) {
                    response.substring(errorStart + 8, errorEnd)
                } else {
                    "Unknown XML-RPC fault"
                }
                Logger.e("XCPngAPI", "Authentication failed: $errorMsg")
                false
            } else {
                Logger.e("XCPngAPI", "Authentication failed - unexpected response format")
                Logger.d("XCPngAPI", "Response: ${response.take(500)}")
                false
            }
        } catch (e: IOException) {
            Logger.e("XCPngAPI", "Network error during authentication: ${e.message}", e)
            false
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
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to get VMs", e)
            emptyList()
        }
    }

    suspend fun startVM(uuid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            xmlRpcCall(buildXmlRequest("VM.start", listOf(uuid, "false", "false")))
            Logger.i("XCPngAPI", "Started VM $uuid")
            true
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to start VM", e)
            false
        }
    }

    suspend fun shutdownVM(uuid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            xmlRpcCall(buildXmlRequest("VM.clean_shutdown", listOf(uuid)))
            Logger.i("XCPngAPI", "Shutdown VM $uuid")
            true
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to shutdown VM", e)
            false
        }
    }

    suspend fun rebootVM(uuid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            xmlRpcCall(buildXmlRequest("VM.clean_reboot", listOf(uuid)))
            Logger.i("XCPngAPI", "Rebooted VM $uuid")
            true
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to reboot VM", e)
            false
        }
    }

    suspend fun hardShutdownVM(uuid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            xmlRpcCall(buildXmlRequest("VM.hard_shutdown", listOf(uuid)))
            Logger.i("XCPngAPI", "Hard shutdown VM $uuid")
            true
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
            xmlRpcCall(buildXmlRequest("VM.hard_reboot", listOf(uuid)))
            Logger.i("XCPngAPI", "Hard reboot VM $uuid")
            true
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to hard reboot VM", e)
            false
        }
    }

    private fun buildXmlRequest(method: String, params: List<String>): String {
        val paramsXml = params.joinToString("") { param ->
            "<param><value>${xmlEscape(param)}</value></param>"
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
                val responseBody = response.body?.string()
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

    private fun parseStringResponse(xml: String): String {
        val start = xml.indexOf("<value>") + 7
        val end = xml.indexOf("</value>", start)
        return if (start > 6 && end > start) xmlUnescape(xml.substring(start, end)) else ""
    }

    // Inverse of xmlEscape — convert XML entity references back to their literal
    // characters. Required because XAPI returns string values with the same
    // entity encoding that we send (e.g. a VM named "A & B" arrives as "A &amp; B").
    private fun xmlUnescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        return s.replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
    }

    private fun parseIntResponse(xml: String): Long {
        return parseStringResponse(xml).toLongOrNull() ?: 0L
    }

    private fun parseBooleanResponse(xml: String): Boolean {
        return parseStringResponse(xml) == "true" || parseStringResponse(xml) == "1"
    }

    private fun parseArrayResponse(xml: String): List<String> {
        val values = mutableListOf<String>()
        var index = 0

        while (true) {
            val start = xml.indexOf("<value>", index)
            if (start == -1) break

            val end = xml.indexOf("</value>", start)
            if (end == -1) break

            values.add(xml.substring(start + 7, end))
            index = end + 8
        }

        return values
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
                    location.replace("http://", "ws://")
                } else if (location.startsWith("https://")) {
                    location.replace("https://", "wss://")
                } else {
                    // Assume it's already a proper URL, construct WebSocket URL
                    "wss://$host/console?ref=$consoleRef&session_id=$sessionId"
                }

                Logger.i("XCPngAPI", "Console URL: $wsUrl")
                wsUrl
            } else {
                // Fallback: construct console URL manually
                val fallbackUrl = "wss://$host/console?ref=$consoleRef&session_id=$sessionId"
                Logger.i("XCPngAPI", "Using fallback console URL: $fallbackUrl")
                fallbackUrl
            }
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to get console URL", e)
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
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Failed to get VM ref for UUID $uuid", e)
            null
        }
    }
}
