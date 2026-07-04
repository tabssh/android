package io.github.tabssh.hypervisor.proxmox
import io.github.tabssh.hypervisor.spice.SpiceConnectionParams
import io.github.tabssh.utils.logging.Logger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Proxmox VE API Client
 */
class ProxmoxApiClient(
    private val host: String,
    private val port: Int = 8006,
    private val username: String,
    private val password: String,
    private val realm: String = "pam",
    private val verifySsl: Boolean = false,
    private val pinnedCertSha256: String? = null
) {

    private val baseUrl = "https://$host:$port/api2/json"
    private val client: OkHttpClient
    private var authTicket: String? = null
    private var csrfToken: String? = null

    /** Phase 1 TLS pin — caller reads after authenticate() to persist
     *  the TOFU capture. Null when verifySsl is false or pin already set. */
    private val capturedPin = io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.CapturedPin()
    fun getCapturedCertSha256(): String? = capturedPin.sha256

    data class ProxmoxNode(
        val node: String,
        val status: String,
        val cpu: Double,
        val maxcpu: Int,
        val mem: Long,
        val maxmem: Long,
        val uptime: Long
    )

    data class ProxmoxVM(
        val vmid: Int,
        val name: String,
        val node: String,
        val type: String,
        val status: String,
        val cpu: Double,
        val maxcpu: Int,
        val mem: Long,
        val maxmem: Long,
        val uptime: Long,
        val ipAddress: String? = null // IP address from guest agent
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
        try { client.dispatcher.cancelAll() } catch (e: Exception) { Logger.w("ProxmoxAPI", "cancelAll: ${e.message}") }
    }

    suspend fun authenticate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("username", "$username@$realm")
                .add("password", password)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/access/ticket")
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val data = json.getJSONObject("data")

                    authTicket = data.getString("ticket")
                    csrfToken = data.getString("CSRFPreventionToken")

                    Logger.i("ProxmoxAPI", "Authentication successful")
                    true
                } else {
                    Logger.e("ProxmoxAPI", "Authentication failed: ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Authentication error", e)
            false
        }
    }

    suspend fun getNodes(): List<ProxmoxNode> = withContext(Dispatchers.IO) {
        try {
            val json = apiGet("/nodes")
            val nodes = mutableListOf<ProxmoxNode>()
            
            val data = json.getJSONArray("data")
            for (i in 0 until data.length()) {
                val node = data.getJSONObject(i)
                nodes.add(ProxmoxNode(
                    node = node.getString("node"),
                    status = node.getString("status"),
                    cpu = node.optDouble("cpu", 0.0),
                    maxcpu = node.optInt("maxcpu", 1),
                    mem = node.optLong("mem", 0),
                    maxmem = node.optLong("maxmem", 1),
                    uptime = node.optLong("uptime", 0)
                ))
            }
            
            Logger.d("ProxmoxAPI", "Retrieved ${nodes.size} nodes")
            nodes
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to get nodes", e)
            emptyList()
        }
    }

    suspend fun getAllVMs(): List<ProxmoxVM> = withContext(Dispatchers.IO) {
        try {
            val json = apiGet("/cluster/resources?type=vm")
            val vms = mutableListOf<ProxmoxVM>()
            
            val data = json.getJSONArray("data")
            for (i in 0 until data.length()) {
                val vm = data.getJSONObject(i)
                vms.add(ProxmoxVM(
                    vmid = vm.getInt("vmid"),
                    name = vm.optString("name", "VM-${vm.getInt("vmid")}"),
                    node = vm.getString("node"),
                    type = vm.getString("type"),
                    status = vm.getString("status"),
                    cpu = vm.optDouble("cpu", 0.0),
                    maxcpu = vm.optInt("maxcpu", 1),
                    mem = vm.optLong("mem", 0),
                    maxmem = vm.optLong("maxmem", 1),
                    uptime = vm.optLong("uptime", 0)
                ))
            }
            
            Logger.d("ProxmoxAPI", "Retrieved ${vms.size} VMs")
            vms
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to get VMs", e)
            emptyList()
        }
    }

    suspend fun startVM(node: String, vmid: Int, type: String = "qemu"): Boolean = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (type == "lxc") "/nodes/$node/lxc/$vmid/status/start" else "/nodes/$node/qemu/$vmid/status/start"
            apiPost(endpoint)
            Logger.i("ProxmoxAPI", "Started VM $vmid")
            true
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to start VM", e)
            false
        }
    }

    suspend fun stopVM(node: String, vmid: Int, type: String = "qemu"): Boolean = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (type == "lxc") "/nodes/$node/lxc/$vmid/status/stop" else "/nodes/$node/qemu/$vmid/status/stop"
            apiPost(endpoint)
            Logger.i("ProxmoxAPI", "Stopped VM $vmid")
            true
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to stop VM", e)
            false
        }
    }

    suspend fun shutdownVM(node: String, vmid: Int, type: String = "qemu"): Boolean = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (type == "lxc") "/nodes/$node/lxc/$vmid/status/shutdown" else "/nodes/$node/qemu/$vmid/status/shutdown"
            apiPost(endpoint)
            Logger.i("ProxmoxAPI", "Shutdown VM $vmid")
            true
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to shutdown VM", e)
            false
        }
    }

    /**
     * Get VM IP address from QEMU guest agent
     */
    suspend fun getVMIPAddress(node: String, vmid: Int, type: String = "qemu"): String? = withContext(Dispatchers.IO) {
        try {
            if (type == "lxc") {
                // For LXC containers, get IP from config
                val json = apiGet("/nodes/$node/lxc/$vmid/interfaces")
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    for (i in 0 until data.length()) {
                        val iface = data.getJSONObject(i)
                        val inet = iface.optString("inet").takeIf { it.isNotEmpty() }
                        if (inet != null && !inet.startsWith("127.")) {
                            return@withContext inet.split("/")[0] // Remove CIDR notation
                        }
                    }
                }
            } else {
                // For QEMU VMs, use guest agent
                try {
                    val json = apiGet("/nodes/$node/qemu/$vmid/agent/network-get-interfaces")
                    val result = json.optJSONObject("data")?.optJSONObject("result")
                    val interfaces = result?.optJSONArray("result")
                    
                    if (interfaces != null) {
                        for (i in 0 until interfaces.length()) {
                            val iface = interfaces.getJSONObject(i)
                            val ipAddresses = iface.optJSONArray("ip-addresses")
                            if (ipAddresses != null) {
                                for (j in 0 until ipAddresses.length()) {
                                    val ipObj = ipAddresses.getJSONObject(j)
                                    val ipType = ipObj.optString("ip-address-type")
                                    val ip = ipObj.optString("ip-address")
                                    if (ipType == "ipv4" && !ip.startsWith("127.")) {
                                        return@withContext ip
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.d("ProxmoxAPI", "Guest agent not available for VM $vmid: ${e.message}")
                }
            }
            null
        } catch (e: Exception) {
            Logger.d("ProxmoxAPI", "Failed to get IP for VM $vmid", e)
            null
        }
    }

    suspend fun rebootVM(node: String, vmid: Int, type: String = "qemu"): Boolean = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (type == "lxc") "/nodes/$node/lxc/$vmid/status/reboot" else "/nodes/$node/qemu/$vmid/status/reboot"
            apiPost(endpoint)
            Logger.i("ProxmoxAPI", "Rebooted VM $vmid")
            true
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to reboot VM", e)
            false
        }
    }

    /**
     * Hard reset a VM (immediate power cycle, like pressing reset button)
     * Unlike reboot, this does not wait for graceful shutdown.
     */
    suspend fun resetVM(node: String, vmid: Int, type: String = "qemu"): Boolean = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (type == "lxc") "/nodes/$node/lxc/$vmid/status/reset" else "/nodes/$node/qemu/$vmid/status/reset"
            apiPost(endpoint)
            Logger.i("ProxmoxAPI", "Reset VM $vmid (hard reset)")
            true
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to reset VM", e)
            false
        }
    }

    /**
     * Terminal proxy result containing ticket and WebSocket URL
     */
    data class TermProxyResult(
        // PVEAuthCookie value (used for the HTTP-upgrade Cookie header).
        val authCookie: String,
        // Per-session termproxy ticket (the value of the `vncticket` query
        // param AND the password half of the WS first-frame auth handshake).
        val termproxyTicket: String,
        // Full Proxmox userid (`user@realm`) — username half of the WS auth
        // handshake. Termproxy expects `<userid>:<termproxyTicket>\n` as the
        // first frame after WS open or it closes the connection within ~10s.
        val userid: String,
        val port: Int,
        val websocketUrl: String
    )

    /**
     * Get terminal proxy for VM console access.
     * This provides serial console access that works even without VM network.
     *
     * @param node Proxmox node name
     * @param vmid VM ID
     * @param type VM type: "qemu" or "lxc"
     * @return TermProxyResult with ticket and WebSocket URL
     * @throws Exception on API failure (message contains Proxmox error detail)
     */
    suspend fun getTermProxy(node: String, vmid: Int, type: String = "qemu"): TermProxyResult = withContext(Dispatchers.IO) {
        try {
            // Use termproxy for serial console (text-based, mobile-friendly)
            val endpoint = if (type == "lxc") {
                "/nodes/$node/lxc/$vmid/termproxy"
            } else {
                "/nodes/$node/qemu/$vmid/termproxy"
            }

            val json = apiPost(endpoint)
            val data = json.optJSONObject("data")

            if (data != null) {
                val ticket = data.getString("ticket")
                val termProxyPort = data.getInt("port")

                // Build WebSocket URL for terminal proxy
                // Format: wss://host:apiPort/api2/json/nodes/{node}/{type}/{vmid}/vncwebsocket?port={termProxyPort}&vncticket={ticket}
                val encodedTicket = java.net.URLEncoder.encode(ticket, "UTF-8")
                val wsEndpoint = if (type == "lxc") {
                    "/nodes/$node/lxc/$vmid/vncwebsocket"
                } else {
                    "/nodes/$node/qemu/$vmid/vncwebsocket"
                }
                // Use this.port (hypervisor API port, usually 8006) for base URL
                val websocketUrl = "wss://$host:${this@ProxmoxApiClient.port}/api2/json$wsEndpoint?port=$termProxyPort&vncticket=$encodedTicket"

                Logger.i("ProxmoxAPI", "Got termproxy ticket for VM $vmid on port $termProxyPort")

                TermProxyResult(
                    authCookie = authTicket ?: "",
                    termproxyTicket = ticket,
                    userid = "$username@$realm",
                    port = termProxyPort,
                    websocketUrl = websocketUrl
                )
            } else {
                // Proxmox returns {"data":null} when the VM has no serial device
                // configured (termproxy requires a serial interface). Extract the
                // actual error text from whichever field Proxmox used. The errors
                // field is typically a JSONObject keyed by endpoint; pull the first
                // value so downstream callers can do a simple string match.
                val errMsg = json.optString("errors", "").takeIf { it.isNotBlank() }
                    ?: json.optJSONObject("errors")?.let { errObj ->
                        val key = errObj.keys().takeIf { it.hasNext() }?.next()
                        key?.let { errObj.optString(it, "").takeIf { v -> v.isNotBlank() } }
                            ?: errObj.toString().takeIf { it.isNotBlank() }
                    }
                    ?: json.optString("message", "").takeIf { it.isNotBlank() }
                    // Null data with no error text still means no serial device —
                    // use a message that triggers the friendly hint downstream.
                    ?: "termproxy: serial interface not defined"
                throw IOException(errMsg)
            }
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to get termproxy", e)
            throw e
        }
    }

    /**
     * Get VNC proxy for VM console access (alternative to termproxy).
     * VNC provides graphical console but is less mobile-friendly.
     *
     * @param node Proxmox node name
     * @param vmid VM ID
     * @param type VM type: "qemu" or "lxc"
     * @return TermProxyResult with ticket and WebSocket URL, or null on failure
     */
    suspend fun getVNCProxy(node: String, vmid: Int, type: String = "qemu"): TermProxyResult? = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (type == "lxc") {
                "/nodes/$node/lxc/$vmid/vncproxy"
            } else {
                "/nodes/$node/qemu/$vmid/vncproxy"
            }

            // websocket=1 tells Proxmox to configure its internal VNC proxy for
            // WebSocket transport; without it the port is opened for raw TCP only.
            val json = apiPost(endpoint, mapOf("websocket" to "1"))
            val data = json.optJSONObject("data")

            if (data != null) {
                val ticket = data.getString("ticket")
                val vncPort = data.getInt("port")

                val encodedTicket = java.net.URLEncoder.encode(ticket, "UTF-8")
                val wsEndpoint = if (type == "lxc") {
                    "/nodes/$node/lxc/$vmid/vncwebsocket"
                } else {
                    "/nodes/$node/qemu/$vmid/vncwebsocket"
                }
                // Use the API port (this.port, e.g. 8006) for the WebSocket outer connection,
                // not the VNC port from the response (which is an internal Proxmox port)
                val websocketUrl = "wss://$host:${this@ProxmoxApiClient.port}/api2/json$wsEndpoint?port=$vncPort&vncticket=$encodedTicket"

                Logger.i("ProxmoxAPI", "Got vncproxy ticket for VM $vmid on port $vncPort")

                TermProxyResult(
                    authCookie = authTicket ?: "",
                    termproxyTicket = ticket,
                    userid = "$username@$realm",
                    port = vncPort,
                    websocketUrl = websocketUrl
                )
            } else {
                Logger.e("ProxmoxAPI", "No data in vncproxy response")
                null
            }
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to get vncproxy", e)
            null
        }
    }

    /**
     * Result of a `/nodes/{node}/qemu/{vmid}/spiceproxy` call.
     *
     * Proxmox returns the same JSON that its web UI hands to `remote-viewer`
     * (the `.vv` config, minus the INI serialisation). We hold on to every
     * field that the SPICE client needs to open a session, plus the metadata
     * that surfaces in the console UI title bar.
     *
     * The [caCert] byte array is the PEM-encoded PVE cluster CA cert that
     * signed the SPICE server's TLS cert. Proxmox escapes the newlines as
     * the literal two-character sequence `\n` — we un-escape before storing
     * so the JNI layer can feed the bytes straight into `g_tls_certificate_new`.
     *
     * @property host SPICE endpoint hostname (Proxmox proxy host).
     * @property port Plain-text SPICE port; may be 0 when Proxmox only
     *   exposes TLS. Never both zero — that combination is rejected upstream.
     * @property tlsPort TLS SPICE port; may be 0 when TLS is disabled.
     * @property password Per-session SPICE ticket.
     * @property caCert PEM bytes of the CA cert to verify [host] against,
     *   or null when Proxmox did not return a `ca` field (extremely rare —
     *   `spiceproxy` always issues one for standard clusters).
     * @property hostSubject Expected cert subject line; feed straight to
     *   libspice's `host-subject` property.
     * @property title Human-readable session title (`title` field from the
     *   API — usually `"VM <vmid> - <name>"`).
     * @property proxy HTTP proxy URL for the SPICE session (the `proxy` field
     *   from the API). libspice sends this over the main channel; on Android
     *   we still forward the value even though the platform does not honour
     *   HTTP_PROXY env vars — the SPICE server uses it internally to advertise
     *   how remote-viewer should tunnel back.
     */
    data class SPICEProxyResult(
        val host: String,
        val port: Int,
        val tlsPort: Int,
        val password: String,
        val caCert: ByteArray?,
        val hostSubject: String?,
        val title: String?,
        val proxy: String?,
    ) {
        /**
         * Convert to a [SpiceConnectionParams] ready for [io.github.tabssh
         * .hypervisor.spice.SpiceClient]. Always sets `tlsVerify=true` — the
         * PEM CA in [caCert] is trusted material issued by the same Proxmox
         * cluster the user already authenticated against, so falling back
         * to `tlsVerify=false` would silently downgrade a chain we have every
         * reason to validate.
         */
        fun toConnectionParams(): SpiceConnectionParams = SpiceConnectionParams(
            host = host,
            port = port,
            tlsPort = tlsPort,
            password = password,
            caCert = caCert,
            hostSubject = hostSubject,
            tlsVerify = true,
        )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SPICEProxyResult) return false
            return host == other.host && port == other.port &&
                tlsPort == other.tlsPort && password == other.password &&
                (caCert?.contentEquals(other.caCert) == true ||
                    (caCert == null && other.caCert == null)) &&
                hostSubject == other.hostSubject &&
                title == other.title && proxy == other.proxy
        }

        override fun hashCode(): Int {
            var r = host.hashCode()
            r = 31 * r + port
            r = 31 * r + tlsPort
            r = 31 * r + password.hashCode()
            r = 31 * r + (caCert?.contentHashCode() ?: 0)
            r = 31 * r + (hostSubject?.hashCode() ?: 0)
            r = 31 * r + (title?.hashCode() ?: 0)
            r = 31 * r + (proxy?.hashCode() ?: 0)
            return r
        }
    }

    /**
     * Fetch a SPICE proxy ticket for a QEMU guest.
     *
     * Only qemu type is supported — Proxmox does not offer SPICE for LXC.
     * The Proxmox web UI passes the client's `Host:` header as the `proxy`
     * form parameter so the returned `.vv` config points remote-viewer at
     * the same address the user browsed to. We do the same: pass this API
     * client's [host] value, which is the address the user configured for
     * this hypervisor connection.
     *
     * Returns null on any failure (network, auth, VM configured without
     * SPICE display). Error text is logged and swallowed to mirror the
     * shape of [getVNCProxy] — the caller decides how to fall back.
     */
    suspend fun getSPICEProxy(node: String, vmid: Int): SPICEProxyResult? = withContext(Dispatchers.IO) {
        try {
            val json = apiPost(
                "/nodes/$node/qemu/$vmid/spiceproxy",
                mapOf("proxy" to host),
            )
            val data = json.optJSONObject("data")
            if (data == null) {
                Logger.e("ProxmoxAPI", "No data in spiceproxy response — VM $vmid likely not configured for SPICE")
                return@withContext null
            }
            val respHost = data.optString("host").takeIf { it.isNotBlank() } ?: run {
                Logger.e("ProxmoxAPI", "spiceproxy response missing host")
                return@withContext null
            }
            val plainPort = data.optString("port").toIntOrNull() ?: 0
            val tlsPort = data.optString("tls-port").toIntOrNull() ?: 0
            if (plainPort == 0 && tlsPort == 0) {
                Logger.e("ProxmoxAPI", "spiceproxy response has neither port nor tls-port")
                return@withContext null
            }
            val password = data.optString("password").takeIf { it.isNotBlank() } ?: run {
                Logger.e("ProxmoxAPI", "spiceproxy response missing password/ticket")
                return@withContext null
            }
            /*
             * Proxmox emits the CA PEM with literal backslash-n between
             * lines rather than real newlines — it embeds the value in a
             * .vv INI file for remote-viewer where a real newline would
             * terminate the key. Un-escape before handing to libspice so
             * the PEM decoder can find the BEGIN/END lines.
             */
            val caPem = data.optString("ca").takeIf { it.isNotBlank() }?.replace("\\n", "\n")
            val caCert = caPem?.toByteArray(Charsets.US_ASCII)
            val hostSubject = data.optString("host-subject").takeIf { it.isNotBlank() }
            val title = data.optString("title").takeIf { it.isNotBlank() }
            val proxy = data.optString("proxy").takeIf { it.isNotBlank() }
            Logger.i("ProxmoxAPI",
                "Got spiceproxy ticket for VM $vmid (host=$respHost, plain=$plainPort, tls=$tlsPort)")
            SPICEProxyResult(
                host = respHost,
                port = plainPort,
                tlsPort = tlsPort,
                password = password,
                caCert = caCert,
                hostSubject = hostSubject,
                title = title,
                proxy = proxy,
            )
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to get spiceproxy", e)
            null
        }
    }

    private fun apiGet(endpoint: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl$endpoint")
            .addHeader("Cookie", "PVEAuthCookie=$authTicket")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (response.isSuccessful && responseBody != null) {
                JSONObject(responseBody)
            } else {
                throw IOException("API request failed: ${response.code}")
            }
        }
    }

    private fun apiPost(endpoint: String, formParams: Map<String, String> = emptyMap()): JSONObject {
        // Proxmox API expects application/x-www-form-urlencoded, not JSON
        val formBody = FormBody.Builder().apply {
            formParams.forEach { (k, v) -> add(k, v) }
        }.build()

        val request = Request.Builder()
            .url("$baseUrl$endpoint")
            .addHeader("Cookie", "PVEAuthCookie=$authTicket")
            .addHeader("CSRFPreventionToken", csrfToken ?: "")
            .post(formBody)
            .build()

        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (response.isSuccessful && responseBody != null) {
                JSONObject(responseBody)
            } else {
                val errorDetail = try {
                    val body = JSONObject(responseBody ?: "{}")
                    // errors may be a flat string or a JSONObject keyed by endpoint
                    // (e.g. {"vmid":"serial interface not defined"}). Extract the
                    // actual text so callers can do simple string matching.
                    body.optString("errors", "").takeIf { it.isNotBlank() }
                        ?: body.optJSONObject("errors")?.let { errObj ->
                            val key = errObj.keys().takeIf { it.hasNext() }?.next()
                            key?.let { errObj.optString(it, "").takeIf { v -> v.isNotBlank() } }
                                ?: errObj.toString().takeIf { it.isNotBlank() }
                        }
                        ?: responseBody?.take(200) ?: ""
                } catch (_: Exception) {
                    responseBody?.take(200) ?: ""
                }
                throw IOException(if (errorDetail.isBlank()) "API request failed: ${response.code}" else errorDetail)
            }
        }
    }
}
