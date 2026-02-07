package io.github.tabssh.hypervisor.vmware
import io.github.tabssh.utils.logging.Logger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * VMware vSphere REST API Client
 */
class VMwareApiClient(
    private val host: String,
    private val username: String,
    private val password: String,
    private val verifySsl: Boolean = false
) {

    private val baseUrl = "https://$host/api"
    private val client: OkHttpClient
    private var sessionId: String? = null

    data class VMwareVM(
        val vm: String,
        val name: String,
        val powerState: String,
        val cpuCount: Int,
        val memoryMB: Long
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

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                sessionId = responseBody.replace("\"", "")
                Logger.i("VMwareAPI", "Authentication successful")
                true
            } else {
                Logger.e("VMwareAPI", "Authentication failed: ${response.code}")
                false
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

    private fun apiGet(endpoint: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl$endpoint")
            .addHeader("vmware-api-session-id", sessionId ?: "")
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
            .addHeader("vmware-api-session-id", sessionId ?: "")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (response.isSuccessful) {
            return if (responseBody != null && responseBody.isNotEmpty()) {
                JSONObject(responseBody)
            } else {
                JSONObject()
            }
        } else {
            throw IOException("API request failed: ${response.code}")
        }
    }
}
