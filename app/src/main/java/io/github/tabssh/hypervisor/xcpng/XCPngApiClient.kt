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
 */
class XCPngApiClient(
    private val host: String,
    private val port: Int = 443,
    private val username: String,
    private val password: String,
    private val verifySsl: Boolean = false
) {

    private val baseUrl = "https://$host:$port"
    private val client: OkHttpClient
    private var sessionId: String? = null

    data class XenVM(
        val uuid: String,
        val name: String,
        val powerState: String,
        val memory: Long,
        val vcpus: Int,
        val isTemplate: Boolean
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
            val xmlRequest = """
                <?xml version="1.0"?>
                <methodCall>
                    <methodName>session.login_with_password</methodName>
                    <params>
                        <param><value>$username</value></param>
                        <param><value>$password</value></param>
                    </params>
                </methodCall>
            """.trimIndent()

            val response = xmlRpcCall(xmlRequest)
            
            // Parse XML response for session ID
            if (response.contains("<value>") && response.contains("OpaqueRef:")) {
                val start = response.indexOf("<value>") + 7
                val end = response.indexOf("</value>", start)
                sessionId = response.substring(start, end)
                Logger.i("XCPngAPI", "Authentication successful")
                true
            } else {
                Logger.e("XCPngAPI", "Authentication failed")
                false
            }
        } catch (e: Exception) {
            Logger.e("XCPngAPI", "Authentication error", e)
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

    private fun buildXmlRequest(method: String, params: List<String>): String {
        val paramsXml = params.joinToString("") { param ->
            "<param><value>$param</value></param>"
        }
        
        return """
            <?xml version="1.0"?>
            <methodCall>
                <methodName>$method</methodName>
                <params>
                    <param><value>$sessionId</value></param>
                    $paramsXml
                </params>
            </methodCall>
        """.trimIndent()
    }

    private fun xmlRpcCall(xml: String): String {
        val requestBody = xml.toRequestBody("text/xml".toMediaType())
        
        val request = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (response.isSuccessful && responseBody != null) {
            return responseBody
        } else {
            throw IOException("XML-RPC call failed: ${response.code}")
        }
    }

    private fun parseStringResponse(xml: String): String {
        val start = xml.indexOf("<value>") + 7
        val end = xml.indexOf("</value>", start)
        return if (start > 6 && end > start) xml.substring(start, end) else ""
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
}
