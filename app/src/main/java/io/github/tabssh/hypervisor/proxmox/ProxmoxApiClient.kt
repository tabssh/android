package io.github.tabssh.hypervisor.proxmox
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

    private fun apiPost(endpoint: String, body: String = ""): JSONObject {
        val requestBody = body.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl$endpoint")
            .addHeader("Cookie", "PVEAuthCookie=$authTicket")
            .addHeader("CSRFPreventionToken", csrfToken ?: "")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (response.isSuccessful && responseBody != null) {
            return JSONObject(responseBody)
        } else {
            throw IOException("API request failed: ${response.code}")
        }
    }
}
