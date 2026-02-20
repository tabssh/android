package io.github.tabssh.hypervisor.proxmox
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
    private val verifySsl: Boolean = false
) {

    private val baseUrl = "https://$host:$port/api2/json"
    private val client: OkHttpClient
    private var authTicket: String? = null
    private var csrfToken: String? = null

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
        
        if (!verifySsl){
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        
        client = builder.build()
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

            val response = client.newCall(request).execute()
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
                        val inet = iface.optString("inet", null)
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
        val ticket: String,
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
     * @return TermProxyResult with ticket and WebSocket URL, or null on failure
     */
    suspend fun getTermProxy(node: String, vmid: Int, type: String = "qemu"): TermProxyResult? = withContext(Dispatchers.IO) {
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
                    ticket = authTicket ?: "",
                    port = termProxyPort,
                    websocketUrl = websocketUrl
                )
            } else {
                Logger.e("ProxmoxAPI", "No data in termproxy response")
                null
            }
        } catch (e: Exception) {
            Logger.e("ProxmoxAPI", "Failed to get termproxy", e)
            null
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

            val json = apiPost(endpoint)
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
                    ticket = authTicket ?: "",
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

    private fun apiGet(endpoint: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl$endpoint")
            .addHeader("Cookie", "PVEAuthCookie=$authTicket")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (response.isSuccessful && responseBody != null) {
            return JSONObject(responseBody)
        } else {
            throw IOException("API request failed: ${response.code}")
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

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (response.isSuccessful && responseBody != null) {
            return JSONObject(responseBody)
        } else {
            val errorDetail = responseBody?.take(200) ?: ""
            throw IOException("API request failed: ${response.code} $errorDetail")
        }
    }
}
