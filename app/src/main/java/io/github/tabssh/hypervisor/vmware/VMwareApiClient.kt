package io.github.tabssh.hypervisor.vmware
import io.github.tabssh.utils.logging.Logger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * VMware vSphere REST API Client
 */
class VMwareApiClient(
    private val host: String,
    private val username: String,
    private val password: String,
    private val verifySsl: Boolean = false,
    private val pinnedCertSha256: String? = null
) {

    private val baseUrl = "https://$host/api"
    private val client: OkHttpClient
    private var sessionId: String? = null

    private val capturedPin = io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.CapturedPin()
    fun getCapturedCertSha256(): String? = capturedPin.sha256

    data class VMwareVM(
        val vm: String,
        val name: String,
        val powerState: String,
        val cpuCount: Int,
        val memoryMB: Long,
        val ipAddress: String? = null // IP from guest info
    )

    init {
        val builder = io.github.tabssh.network.SharedHttpClient.client.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.installTrust(
            builder, verifySsl, pinnedCertSha256, capturedPin, host, 443
        )
        client = builder.build()
    }

    /** Cancel any in-flight HTTP calls. Safe to call from Activity.onDestroy(). */
    fun cancelAll() {
        try { client.dispatcher.cancelAll() } catch (e: Exception) { Logger.w("VMwareAPI", "cancelAll: ${e.message}") }
    }

    suspend fun authenticate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val credentials = "$username:$password"
            val encodedCredentials = android.util.Base64.encodeToString(
                credentials.toByteArray(),
                android.util.Base64.NO_WRAP
            )

            val request = Request.Builder()
                .url("$baseUrl/session")
                .post("".toRequestBody())
                .addHeader("Authorization", "Basic $encodedCredentials")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    sessionId = responseBody.replace("\"", "")
                    Logger.i("VMwareAPI", "Authentication successful")
                    true
                } else {
                    Logger.e("VMwareAPI", "Authentication failed: ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Logger.e("VMwareAPI", "Authentication error", e)
            false
        }
    }

    suspend fun getAllVMs(): List<VMwareVM> = withContext(Dispatchers.IO) {
        try {
            val json = apiGet("/vcenter/vm")
            val vms = mutableListOf<VMwareVM>()
            
            val vmArray = json.getJSONArray("value")
            for (i in 0 until vmArray.length()) {
                val vm = vmArray.getJSONObject(i)
                
                // Get detailed info for each VM
                val vmId = vm.getString("vm")
                val detailJson = apiGet("/vcenter/vm/$vmId")
                val detail = detailJson.getJSONObject("value")
                
                vms.add(VMwareVM(
                    vm = vmId,
                    name = vm.getString("name"),
                    powerState = vm.getString("power_state"),
                    cpuCount = detail.getJSONObject("cpu").getInt("count"),
                    memoryMB = detail.getJSONObject("memory").getLong("size_MiB")
                ))
            }
            
            Logger.d("VMwareAPI", "Retrieved ${vms.size} VMs")
            vms
        } catch (e: Exception) {
            Logger.e("VMwareAPI", "Failed to get VMs", e)
            emptyList()
        }
    }

    suspend fun startVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            apiPost("/vcenter/vm/$vmId/power?action=start")
            Logger.i("VMwareAPI", "Started VM $vmId")
            true
        } catch (e: Exception) {
            Logger.e("VMwareAPI", "Failed to start VM", e)
            false
        }
    }

    suspend fun stopVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            apiPost("/vcenter/vm/$vmId/power?action=stop")
            Logger.i("VMwareAPI", "Stopped VM $vmId")
            true
        } catch (e: Exception) {
            Logger.e("VMwareAPI", "Failed to stop VM", e)
            false
        }
    }

    suspend fun resetVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            apiPost("/vcenter/vm/$vmId/power?action=reset")
            Logger.i("VMwareAPI", "Reset VM $vmId")
            true
        } catch (e: Exception) {
            Logger.e("VMwareAPI", "Failed to reset VM", e)
            false
        }
    }

    /**
     * GET an API endpoint. Transparently handles session expiry: vSphere
     * session tokens time out after the server's idle timeout (default
     * 30 min), after which every call returns 401 until we re-authenticate.
     * On 401 we call [authenticate] once and retry the request; if the
     * retry still fails or we have no session at all, the error propagates.
     */
    private suspend fun apiGet(endpoint: String, isRetry: Boolean = false): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl$endpoint")
            .addHeader("vmware-api-session-id", sessionId ?: "")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 401 && !isRetry && sessionId != null) {
            response.close()
            Logger.d("VMwareAPI", "Session expired on GET $endpoint — re-authenticating")
            if (authenticate()) return apiGet(endpoint, isRetry = true)
        }
        return response.use { r ->
            val responseBody = r.body?.string()
            if (r.isSuccessful && responseBody != null) {
                JSONObject(responseBody)
            } else {
                throw IOException("API request failed: ${r.code}")
            }
        }
    }

    /**
     * POST to an API endpoint. Same re-auth-once-on-401 semantics as
     * [apiGet] — see that function's doc for the rationale.
     */
    private suspend fun apiPost(endpoint: String, body: String = "", isRetry: Boolean = false): JSONObject {
        val requestBody = body.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl$endpoint")
            .addHeader("vmware-api-session-id", sessionId ?: "")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 401 && !isRetry && sessionId != null) {
            response.close()
            Logger.d("VMwareAPI", "Session expired on POST $endpoint — re-authenticating")
            if (authenticate()) return apiPost(endpoint, body, isRetry = true)
        }
        return response.use { r ->
            val responseBody = r.body?.string()
            if (r.isSuccessful) {
                if (responseBody != null && responseBody.isNotEmpty()) JSONObject(responseBody)
                else JSONObject()
            } else {
                throw IOException("API request failed: ${r.code}")
            }
        }
    }

    // ── VNC-via-vmx console (vim25 SOAP) ──────────────────────────────────────

    /** Connection details for a VM's RemoteDisplay VNC server, read from the vmx via vim25 SOAP. */
    data class VncConsoleInfo(
        val host: String,
        val port: Int,
        val password: String?
    )

    /** One vim25 SOAP response: body plus the vmware_soap_session cookie when the server set one. */
    private data class SoapResponse(val body: String, val sessionCookie: String?)

    /**
     * Read the RemoteDisplay.vnc.* options from the VM's vmx via the vim25 SOAP endpoint
     * (/sdk) and resolve the ESXi host that runs the VM. The vSphere REST API does not
     * expose extraConfig, so this is the only supported read path. Throws [IOException]
     * with a user-actionable message when VNC is not enabled on the VM.
     */
    suspend fun getVncConsoleInfo(vmId: String): VncConsoleInfo = withContext(Dispatchers.IO) {
        // ServiceInstance is the one well-known MoRef; everything else is resolved from it
        val content = soapCall(
            soapEnvelope(
                "<vim25:RetrieveServiceContent>" +
                    "<vim25:_this type=\"ServiceInstance\">ServiceInstance</vim25:_this>" +
                    "</vim25:RetrieveServiceContent>"
            ),
            cookie = null
        )
        // Resolve the SessionManager MoRef instead of hardcoding ha-sessionmgr (fails on vCenter)
        val sessionManager = parseTagText(content.body, "sessionManager")
            ?: throw IOException("vim25: ServiceContent has no sessionManager")
        val propertyCollector = parseTagText(content.body, "propertyCollector")
            ?: throw IOException("vim25: ServiceContent has no propertyCollector")

        val login = soapCall(
            soapEnvelope(
                "<vim25:Login>" +
                    "<vim25:_this type=\"SessionManager\">${xmlEscape(sessionManager)}</vim25:_this>" +
                    "<vim25:userName>${xmlEscape(username)}</vim25:userName>" +
                    "<vim25:password>${xmlEscape(password)}</vim25:password>" +
                    "</vim25:Login>"
            ),
            cookie = null
        )
        val cookie = login.sessionCookie
            ?: throw IOException("vim25: Login returned no vmware_soap_session cookie")

        try {
            val props = soapCall(
                soapEnvelope(
                    "<vim25:RetrievePropertiesEx>" +
                        "<vim25:_this type=\"PropertyCollector\">${xmlEscape(propertyCollector)}</vim25:_this>" +
                        "<vim25:specSet>" +
                        "<vim25:propSet>" +
                        "<vim25:type>VirtualMachine</vim25:type>" +
                        "<vim25:pathSet>config.extraConfig</vim25:pathSet>" +
                        "<vim25:pathSet>runtime.host</vim25:pathSet>" +
                        "</vim25:propSet>" +
                        "<vim25:objectSet>" +
                        "<vim25:obj type=\"VirtualMachine\">${xmlEscape(vmId)}</vim25:obj>" +
                        "</vim25:objectSet>" +
                        "</vim25:specSet>" +
                        "<vim25:options/>" +
                        "</vim25:RetrievePropertiesEx>"
                ),
                cookie
            )
            val (extraConfig, hostRef) = parseVmDisplayProps(props.body)

            val enabled = extraConfig["RemoteDisplay.vnc.enabled"]
            val port = extraConfig["RemoteDisplay.vnc.port"]?.trim()?.toIntOrNull()
            if (!"TRUE".equals(enabled, ignoreCase = true) || port == null || port !in 1..65535) {
                throw IOException(
                    "VNC is not enabled on this VM. Set RemoteDisplay.vnc.enabled = TRUE and " +
                        "RemoteDisplay.vnc.port in the VM's advanced settings (Edit Settings → " +
                        "Advanced → Configuration Parameters), then power-cycle the VM."
                )
            }
            val vncPassword = extraConfig["RemoteDisplay.vnc.password"]?.takeIf { it.isNotEmpty() }

            // On vCenter the VNC server listens on the ESXi host running the VM, not on
            // vCenter itself; resolve the HostSystem name and fall back to our own host.
            val consoleHost = resolveHostSystemName(hostRef, propertyCollector, cookie) ?: host
            Logger.i("VMwareAPI", "VNC console for $vmId at $consoleHost:$port")
            VncConsoleInfo(host = consoleHost, port = port, password = vncPassword)
        } finally {
            try {
                soapCall(
                    soapEnvelope(
                        "<vim25:Logout>" +
                            "<vim25:_this type=\"SessionManager\">${xmlEscape(sessionManager)}</vim25:_this>" +
                            "</vim25:Logout>"
                    ),
                    cookie
                )
            } catch (e: Exception) {
                Logger.w("VMwareAPI", "vim25 Logout failed: ${e.message}")
            }
        }
    }

    /**
     * Resolve the display name of the HostSystem MoRef from runtime.host. Returns null
     * (caller falls back to this client's host) when the ref is missing or lookup fails —
     * on standalone ESXi the API host and the VNC host are the same machine anyway.
     */
    private fun resolveHostSystemName(hostRef: String?, propertyCollector: String, cookie: String): String? {
        if (hostRef.isNullOrBlank()) return null
        return try {
            val resp = soapCall(
                soapEnvelope(
                    "<vim25:RetrievePropertiesEx>" +
                        "<vim25:_this type=\"PropertyCollector\">${xmlEscape(propertyCollector)}</vim25:_this>" +
                        "<vim25:specSet>" +
                        "<vim25:propSet>" +
                        "<vim25:type>HostSystem</vim25:type>" +
                        "<vim25:pathSet>name</vim25:pathSet>" +
                        "</vim25:propSet>" +
                        "<vim25:objectSet>" +
                        "<vim25:obj type=\"HostSystem\">${xmlEscape(hostRef)}</vim25:obj>" +
                        "</vim25:objectSet>" +
                        "</vim25:specSet>" +
                        "<vim25:options/>" +
                        "</vim25:RetrievePropertiesEx>"
                ),
                cookie
            )
            parseHostSystemName(resp.body)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Logger.w("VMwareAPI", "HostSystem name lookup failed, falling back to $host: ${e.message}")
            null
        }
    }

    /** POST one SOAP envelope to https://host/sdk, reusing the pinned/TOFU OkHttp client. */
    private fun soapCall(envelope: String, cookie: String?): SoapResponse {
        val builder = Request.Builder()
            .url("https://$host/sdk")
            .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .addHeader("SOAPAction", "\"urn:vim25/5.5\"")
        if (cookie != null) builder.addHeader("Cookie", cookie)
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val fault = parseTagText(body, "faultstring")
                val detail = if (fault != null) ": $fault" else ""
                throw IOException("vim25 SOAP call failed: ${response.code}$detail")
            }
            val sessionCookie = response.headers("Set-Cookie")
                .firstOrNull { it.startsWith("vmware_soap_session") }
                ?.substringBefore(';')
            return SoapResponse(body, sessionCookie)
        }
    }

    /** Build the standard vim25 SOAP envelope around one operation body. */
    private fun soapEnvelope(body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "xmlns:vim25=\"urn:vim25\">" +
            "<soapenv:Body>$body</soapenv:Body>" +
            "</soapenv:Envelope>"

    /** Escape the five XML special characters for safe embedding in a SOAP envelope. */
    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun newParser(xml: String): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        return parser
    }

    /** Return the text of the first element named [tag] anywhere in [xml], or null. */
    private fun parseTagText(xml: String, tag: String): String? {
        return try {
            val parser = newParser(xml)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == tag) return parser.nextText()
                event = parser.next()
            }
            null
        } catch (e: Exception) {
            Logger.w("VMwareAPI", "XML parse for <$tag> failed: ${e.message}")
            null
        }
    }

    /**
     * Parse a RetrievePropertiesEx response for a VirtualMachine: collects the
     * config.extraConfig OptionValue key/value pairs and the runtime.host MoRef value.
     */
    private fun parseVmDisplayProps(xml: String): Pair<Map<String, String>, String?> {
        val extraConfig = mutableMapOf<String, String>()
        var hostRef: String? = null
        var currentProp: String? = null
        var currentKey: String? = null
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    // Each propSet carries <name>config.extraConfig|runtime.host</name>
                    "name" -> currentProp = parser.nextText()
                    "key" -> if (currentProp == "config.extraConfig") currentKey = parser.nextText()
                    "value" -> if (currentProp == "config.extraConfig" && currentKey != null) {
                        extraConfig[currentKey] = parser.nextText()
                        currentKey = null
                    }
                    // runtime.host's <val> is a text-only ManagedObjectReference
                    "val" -> if (currentProp == "runtime.host") hostRef = parser.nextText()
                }
            }
            event = parser.next()
        }
        return Pair(extraConfig, hostRef)
    }

    /** Parse a RetrievePropertiesEx response for a HostSystem's name property. */
    private fun parseHostSystemName(xml: String): String? {
        var currentProp: String? = null
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "name" -> currentProp = parser.nextText()
                    "val" -> if (currentProp == "name") return parser.nextText()
                }
            }
            event = parser.next()
        }
        return null
    }

    /**
     * Detect if this is vCenter or standalone ESXi
     * vCenter has datacenter management, standalone ESXi does not
     */
    suspend fun isVCenter(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // Try to list datacenters - this is vCenter-specific
            apiGet("/vcenter/datacenter")
            Logger.d("VMwareAPI", "Datacenter endpoint accessible - this is vCenter")
            true
        } catch (e: Exception) {
            // Datacenter endpoint not available - this is standalone ESXi
            Logger.d("VMwareAPI", "Datacenter endpoint not available - this is standalone ESXi")
            false
        }
    }
}
