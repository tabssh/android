package io.github.tabssh.hypervisor.xcpng

import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Xen Orchestra REST API Client (Auto-detecting version)
 *
 * Supports Xen Orchestra (XO) server connections via REST API
 * Auto-detects API version (tries v6, then v5, then v0)
 * Provides full VM management, snapshots, backups, and resource pool operations
 *
 * API Documentation: https://xen-orchestra.com/docs/rest_api.html
 */
class XenOrchestraApiClient(
    private val host: String,
    private val port: Int = 443,
    private val email: String,
    private val password: String,
    private val verifySsl: Boolean = false,
    private val pinnedCertSha256: String? = null,
    /**
     * Invoked synchronously, on the handshake thread, the instant
     * [capturedPin] receives a new SHA-256 — TOFU accept, silent
     * system-CA accept, or an explicit user ACCEPT_AND_PIN on a changed
     * cert. Callers use this to persist [getCapturedCertSha256] to the DB
     * right away instead of waiting for a later call on this same shared
     * client to finish without throwing.
     */
    private val onPinCaptured: (() -> Unit)? = null
) {

    private val baseUrl = "https://$host:$port"
    private val client: OkHttpClient

    private val capturedPin = io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.CapturedPin()
    fun getCapturedCertSha256(): String? = capturedPin.sha256

    // Authentication state.
    // @Volatile: written by the authenticate/refresh coroutines and read by
    // every request builder, which can run on a different IO thread.
    @Volatile private var authToken: String? = null
    @Volatile private var userId: String? = null
    @Volatile private var tokenExpiresAt: Long? = null

    // OAuth2 state (if supported by XO)
    @Volatile private var refreshToken: String? = null

    // API version (auto-detected)
    @Volatile private var apiPrefix: String = "/rest/v0"
    @Volatile private var detectedApiVersion: String? = null

    companion object {
        private const val TAG = "XenOrchestraAPI"

        // API versions to try in order (newest first)
        private val API_VERSIONS = listOf("/rest/v6", "/rest/v5", "/rest/v0")

        /**
         * Accept a console URL handed back by the XO REST API only when it is a
         * WebSocket URL pointing at the host the user configured.
         *
         * The console URL is dialled with the XO auth token attached, so a
         * compromised or merely misconfigured XO answering `wss://attacker/…`
         * would hand that token straight to a third party. Cross-host redirection
         * has no legitimate use here: XO always proxies consoles itself.
         */
        internal fun isAcceptableConsoleUrl(url: String, expectedHost: String): Boolean {
            val parsed = try { java.net.URI(url) } catch (_: Exception) { return false }
            val scheme = parsed.scheme?.lowercase() ?: return false
            if (scheme != "ws" && scheme != "wss") return false
            val urlHost = parsed.host ?: return false
            return urlHost.equals(expectedHost, ignoreCase = true)
        }
    }
    
    /**
     * Data Models
     */
    
    data class XoAuthToken(
        val token: String,
        val userId: String,
        val expiresAt: Long? = null
    )
    
    data class XoVM(
        val id: String,
        val uuid: String,
        val name_label: String,
        val power_state: String,
        val memory: Long,
        val vcpus: Int,
        val type: String,
        val tags: List<String> = emptyList(),
        val mainIpAddress: String? = null,
        val `$pool`: String? = null,
        val `$container`: String? = null,
        val os_version: Map<String, String>? = null
    )
    
    data class XoPool(
        val id: String,
        val uuid: String,
        val name_label: String,
        val description: String? = null,
        val master: String,
        val default_SR: String? = null
    )
    
    data class XoHost(
        val id: String,
        val uuid: String,
        val name_label: String,
        val hostname: String,
        val memory_total: Long,
        val memory_free: Long,
        val enabled: Boolean,
        val `$pool`: String? = null
    )
    
    data class XoSnapshot(
        val id: String,
        val uuid: String,
        val name_label: String,
        val snapshot_time: Long,
        val `$snapshot_of`: String
    )
    
    data class XoBackupJob(
        val id: String,
        val name: String,
        val mode: String,
        val enabled: Boolean,
        val schedule: String? = null,
        val vms: List<String> = emptyList()
    )
    
    data class XoBackupRun(
        val id: String,
        val jobId: String,
        val status: String,
        val start: Long,
        val end: Long? = null,
        val result: String? = null
    )
    
    /**
     * Initialize OkHttpClient with optional SSL verification bypass
     */
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

        Logger.d(TAG, "XenOrchestraApiClient initialized for $baseUrl")
    }

    /** Cancel any in-flight REST calls. Safe to call from Activity.onDestroy(). */
    fun cancelAll() {
        try { client.dispatcher.cancelAll() } catch (e: Exception) { Logger.w(TAG, "cancelAll: ${e.message}") }
    }
    
    /**
     * Authentication - Basic Auth with email/password
     * Auto-detects API version by trying v6, v5, v0 in order
     *
     * POST /rest/vX/users/signin
     * Body: {"email": "user@example.com", "password": "..."}
     * Response: {"token": "...", "userId": "..."}
     */
    suspend fun authenticate(): Boolean = withContext(Dispatchers.IO) {
        // If we already detected a working API version, use it
        if (detectedApiVersion != null) {
            return@withContext authenticateWithVersion(apiPrefix)
        }

        // Try each API version in order until one works
        Logger.d(TAG, "Auto-detecting API version for $baseUrl")

        for (version in API_VERSIONS) {
            Logger.d(TAG, "Trying API version: $version")
            if (authenticateWithVersion(version)) {
                apiPrefix = version
                detectedApiVersion = version
                Logger.i(TAG, "Detected working API version: $version")
                return@withContext true
            }
        }

        Logger.e(TAG, "Authentication failed: No compatible API version found")
        false
    }

    /**
     * Attempt authentication with a specific API version
     */
    private suspend fun authenticateWithVersion(version: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Creating authentication token with XO API $version")

            // Create authentication token using Basic Auth
            // POST /rest/v0/users/me/authentication_tokens
            val credentials = "$email:$password"
            val encodedCredentials = android.util.Base64.encodeToString(
                credentials.toByteArray(),
                android.util.Base64.NO_WRAP
            )

            val request = Request.Builder()
                .url("$baseUrl$version/users/me/authentication_tokens")
                // Empty body for token creation
                .post("".toRequestBody())
                .addHeader("Authorization", "Basic $encodedCredentials")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val responseJson = JSONObject(responseBody)
                        val token = responseJson.optString("token")
                        if (token.isNotEmpty()) {
                            authToken = token
                            detectedApiVersion = version
                            // The token authenticates every later REST call and the
                            // console WebSocket — no prefix of it belongs in a log.
                            Logger.i(TAG, "Authentication successful with $version, token: xxxxx")
                            true
                        } else {
                            // Log response length/status only — the body may
                            // contain credential or session data.
                            Logger.d(TAG, "No token in response (HTTP ${response.code}, ${responseBody.length} bytes)")
                            false
                        }
                    } else {
                        Logger.d(TAG, "Empty response body")
                        false
                    }
                } else {
                    Logger.d(TAG, "Authentication with $version failed: HTTP ${response.code}")
                    false
                }
            }

        } catch (e: IOException) {
            Logger.d(TAG, "Network error with $version: ${e.message}")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.d(TAG, "Error with $version: ${e.message}")
            false
        }
    }

    /**
     * Get the detected API version (for debugging/display)
     */
    fun getDetectedApiVersion(): String? = detectedApiVersion
    
    /**
     * Build authenticated request with authorization header
     */
    private fun buildAuthenticatedRequest(
        url: String,
        method: String = "GET",
        body: RequestBody? = null
    ): Request {
        val builder = Request.Builder()
            .url(url)
        
        // Add authorization header if authenticated
        authToken?.let {
            // Use authenticationToken cookie (XO preferred method)
            builder.addHeader("Cookie", "authenticationToken=$it")
        }
        
        // Add method and body
        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post(body ?: "".toRequestBody())
            "PUT" -> builder.put(body ?: "".toRequestBody())
            "DELETE" -> builder.delete(body)
            "PATCH" -> builder.patch(body ?: "".toRequestBody())
        }
        
        return builder.build()
    }
    
    /**
     * Execute HTTP request with error handling
     */
    private suspend fun executeRequest(request: Request): Response = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(request).execute()

            // Check for token expiry (401 Unauthorized)
            if (response.code == 401 && authToken != null) {
                Logger.w(TAG, "Token expired, attempting re-authentication")
                // Close the 401 response before retrying — leaving it open leaks the connection
                response.close()
                if (authenticate()) {
                    val newRequest = buildAuthenticatedRequest(
                        request.url.toString(),
                        request.method,
                        request.body
                    )
                    return@withContext client.newCall(newRequest).execute()
                }
                // Re-auth failed. Falling through here returned the response that
                // was just closed above, so every caller's response.body?.string()
                // threw IllegalStateException("closed") and the real cause — an
                // authentication failure — was lost.
                throw IOException("Xen Orchestra request failed: 401 (re-authentication failed)")
            }

            response
        } catch (e: IOException) {
            Logger.e(TAG, "Network error: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Handle API error responses
     */
    private fun handleApiError(response: Response) {
        val errorBody = response.body?.string()
        try {
            if (errorBody != null) {
                val errorJson = JSONObject(errorBody)
                val message = errorJson.optString("message", "Unknown error")
                val code = errorJson.optString("code", "UNKNOWN")
                Logger.e(TAG, "API Error [$code]: $message")
            } else {
                Logger.e(TAG, "API Error: HTTP ${response.code} with no error body")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error parsing API error: ${e.message}")
            // Length only: an XO error body can echo the request, including the
            // Authorization header value or a console ticket.
            Logger.e(TAG, "Unparseable error body (${errorBody?.length ?: 0} chars)")
        }
    }
    
    /**
     * Logout and clear authentication
     */
    fun logout() {
        authToken = null
        userId = null
        tokenExpiresAt = null
        refreshToken = null
        Logger.d(TAG, "Logged out, tokens cleared")
    }
    
    // ========================================
    // VM Management APIs
    // ========================================
    
    /**
     * List all VMs from Xen Orchestra
     * 
     * GET /rest/v0/vms
     * Returns: Array of VM objects
     */
    suspend fun listVMs(): List<XoVM> = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching VM list from Xen Orchestra")

            // Without ?fields= the collection endpoint returns href strings, not objects.
            val fields = "id,uuid,name_label,power_state,memory,CPUs,type,tags,mainIpAddress,\$pool,\$container,os_version"
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/vms?fields=$fields")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val jsonArray = JSONArray(body)
                    val vms = mutableListOf<XoVM>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val vmJson = jsonArray.getJSONObject(i)
                        
                        // Filter out templates
                        val type = vmJson.optString("type", "")
                        if (type == "VM-template") {
                            continue
                        }
                        
                        // Parse VM object. XO serves memory as {size,…} and vCPUs as CPUs {max,number}.
                        val vm = XoVM(
                            id = vmJson.getString("id"),
                            uuid = vmJson.getString("uuid"),
                            name_label = vmJson.getString("name_label"),
                            power_state = vmJson.getString("power_state"),
                            memory = vmJson.optJSONObject("memory")?.optLong("size") ?: 0L,
                            vcpus = vmJson.optJSONObject("CPUs")?.optInt("max") ?: 0,
                            type = type,
                            tags = parseJsonArray(vmJson.optJSONArray("tags")),
                            mainIpAddress = vmJson.optString("mainIpAddress").takeIf { it.isNotEmpty() },
                            `$pool` = vmJson.optString("\$pool").takeIf { it.isNotEmpty() },
                            `$container` = vmJson.optString("\$container").takeIf { it.isNotEmpty() },
                            os_version = parseJsonObject(vmJson.optJSONObject("os_version"))
                        )
                        
                        vms.add(vm)
                    }
                    
                    Logger.i(TAG, "Retrieved ${vms.size} VMs from Xen Orchestra")
                    vms
                } else {
                    Logger.e(TAG, "Empty response body when listing VMs")
                    emptyList()
                }
            } else {
                handleApiError(response)
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error listing VMs: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get single VM details
     * 
     * GET /rest/v0/vms/:id
     */
    suspend fun getVM(vmId: String): XoVM? = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching VM details for: $vmId")
            
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/vms/$vmId")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val vmJson = JSONObject(body)
                    
                    // XO serves memory as {size,…} and vCPUs as CPUs {max,number}.
                    XoVM(
                        id = vmJson.getString("id"),
                        uuid = vmJson.getString("uuid"),
                        name_label = vmJson.getString("name_label"),
                        power_state = vmJson.getString("power_state"),
                        memory = vmJson.optJSONObject("memory")?.optLong("size") ?: 0L,
                        vcpus = vmJson.optJSONObject("CPUs")?.optInt("max") ?: 0,
                        type = vmJson.optString("type", ""),
                        tags = parseJsonArray(vmJson.optJSONArray("tags")),
                        mainIpAddress = vmJson.optString("mainIpAddress").takeIf { it.isNotEmpty() },
                        `$pool` = vmJson.optString("\$pool").takeIf { it.isNotEmpty() },
                        `$container` = vmJson.optString("\$container").takeIf { it.isNotEmpty() },
                        os_version = parseJsonObject(vmJson.optJSONObject("os_version"))
                    )
                } else {
                    null
                }
            } else {
                handleApiError(response)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting VM $vmId: ${e.message}", e)
            null
        }
    }
    
    /**
     * Start a VM
     *
     * POST /rest/v0/vms/:id/actions/start
     */
    suspend fun startVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Starting VM: $vmId")

            val request = buildAuthenticatedRequest(
                "$baseUrl$apiPrefix/vms/$vmId/actions/start",
                "POST"
            )
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                Logger.i(TAG, "VM $vmId start command sent successfully")
                true
            } else {
                handleApiError(response)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error starting VM $vmId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Stop a VM (clean shutdown)
     * 
     * POST /rest/v0/vms/:id/actions/clean_shutdown
     * POST /rest/v0/vms/:id/actions/hard_shutdown (if force=true)
     */
    suspend fun stopVM(vmId: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val action = if (force) "hard_shutdown" else "clean_shutdown"
            Logger.d(TAG, "Stopping VM: $vmId (force=$force)")

            val request = buildAuthenticatedRequest(
                "$baseUrl$apiPrefix/vms/$vmId/actions/$action",
                "POST"
            )
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                Logger.i(TAG, "VM $vmId $action command sent successfully")
                true
            } else {
                handleApiError(response)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error stopping VM $vmId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Reboot a VM (graceful restart)
     *
     * POST /rest/v0/vms/:id/actions/clean_reboot
     */
    suspend fun rebootVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Rebooting VM: $vmId")

            val request = buildAuthenticatedRequest(
                "$baseUrl$apiPrefix/vms/$vmId/actions/clean_reboot",
                "POST"
            )
            val response = executeRequest(request)

            if (response.isSuccessful) {
                Logger.i(TAG, "VM $vmId restart command sent successfully")
                true
            } else {
                handleApiError(response)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error rebooting VM $vmId: ${e.message}", e)
            false
        }
    }

    /**
     * Hard reset a VM (immediate power cycle)
     * Unlike reboot, this does not wait for graceful shutdown.
     *
     * POST /rest/v0/vms/:id/actions/hard_reboot
     */
    suspend fun resetVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Resetting VM (hard): $vmId")

            val request = buildAuthenticatedRequest(
                "$baseUrl$apiPrefix/vms/$vmId/actions/hard_reboot",
                "POST"
            )
            val response = executeRequest(request)

            if (response.isSuccessful) {
                Logger.i(TAG, "VM $vmId reset command sent successfully")
                true
            } else {
                handleApiError(response)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error resetting VM $vmId: ${e.message}", e)
            false
        }
    }

    /**
     * Suspend a VM
     * 
     * POST /rest/v0/vms/:id/actions/suspend
     */
    suspend fun suspendVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Suspending VM: $vmId")

            val request = buildAuthenticatedRequest(
                "$baseUrl$apiPrefix/vms/$vmId/actions/suspend",
                "POST"
            )
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                Logger.i(TAG, "VM $vmId suspend command sent successfully")
                true
            } else {
                handleApiError(response)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error suspending VM $vmId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Resume a suspended VM
     * 
     * POST /rest/v0/vms/:id/actions/resume
     */
    suspend fun resumeVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Resuming VM: $vmId")

            val request = buildAuthenticatedRequest(
                "$baseUrl$apiPrefix/vms/$vmId/actions/resume",
                "POST"
            )
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                Logger.i(TAG, "VM $vmId resume command sent successfully")
                true
            } else {
                handleApiError(response)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error resuming VM $vmId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get VM IP address from mainIpAddress field
     */
    suspend fun getVMIPAddress(vmId: String): String? = withContext(Dispatchers.IO) {
        try {
            val vm = getVM(vmId)
            vm?.mainIpAddress
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting VM IP for $vmId: ${e.message}", e)
            null
        }
    }
    
    /**
     * Helper: Parse JSON array to List<String>
     */
    private fun parseJsonArray(jsonArray: JSONArray?): List<String> {
        if (jsonArray == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list
    }
    
    /**
     * Helper: Parse JSON object to Map<String, String>
     */
    private fun parseJsonObject(jsonObject: JSONObject?): Map<String, String>? {
        if (jsonObject == null) return null
        val map = mutableMapOf<String, String>()
        jsonObject.keys().forEach { key ->
            map[key] = jsonObject.getString(key)
        }
        return map
    }
    
    // ========================================
    // Snapshot Management APIs
    // ========================================
    
    /**
     * List snapshots for a VM
     * 
     * GET /rest/v0/vms/:id/snapshots
     */
    suspend fun listSnapshots(vmId: String): List<XoSnapshot> = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching snapshots for VM: $vmId")

            // Without ?fields= the collection endpoint returns href strings, not objects.
            val fields = "id,uuid,name_label,snapshot_time,\$snapshot_of"
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/vms/$vmId/snapshots?fields=$fields")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val jsonArray = JSONArray(body)
                    val snapshots = mutableListOf<XoSnapshot>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val snapJson = jsonArray.getJSONObject(i)
                        
                        val snapshot = XoSnapshot(
                            id = snapJson.getString("id"),
                            uuid = snapJson.getString("uuid"),
                            name_label = snapJson.getString("name_label"),
                            snapshot_time = snapJson.getLong("snapshot_time"),
                            `$snapshot_of` = snapJson.getString("\$snapshot_of")
                        )
                        
                        snapshots.add(snapshot)
                    }
                    
                    Logger.i(TAG, "Retrieved ${snapshots.size} snapshots for VM $vmId")
                    snapshots
                } else {
                    Logger.e(TAG, "Empty response when listing snapshots")
                    emptyList()
                }
            } else {
                handleApiError(response)
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error listing snapshots for VM $vmId: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Create a snapshot of a VM
     * 
     * POST /rest/v0/vms/:id/snapshots
     * Body: {"name": "...", "description": "..."}
     */
    suspend fun createSnapshot(
        vmId: String,
        name: String,
        description: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Creating snapshot '$name' for VM: $vmId")
            
            val json = JSONObject().apply {
                put("name", name)
                if (description.isNotEmpty()) {
                    put("description", description)
                }
            }
            
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = buildAuthenticatedRequest(
                "$baseUrl$apiPrefix/vms/$vmId/snapshots",
                "POST",
                body
            )
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                Logger.i(TAG, "Snapshot '$name' created successfully for VM $vmId")
                true
            } else {
                handleApiError(response)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error creating snapshot for VM $vmId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Delete a snapshot
     * 
     * DELETE /rest/v0/vms/:vmId/snapshots/:snapshotId
     */
    suspend fun deleteSnapshot(vmId: String, snapshotId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Deleting snapshot: $snapshotId")
            
            val request = buildAuthenticatedRequest(
                "$baseUrl$apiPrefix/vms/$vmId/snapshots/$snapshotId",
                "DELETE"
            )
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                Logger.i(TAG, "Snapshot $snapshotId deleted successfully")
                true
            } else {
                handleApiError(response)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error deleting snapshot $snapshotId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Revert VM to a snapshot
     * 
     * POST /rest/v0/vms/:vmId/snapshots/:snapshotId/revert
     */
    suspend fun revertSnapshot(vmId: String, snapshotId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Reverting VM $vmId to snapshot: $snapshotId")
            
            val request = buildAuthenticatedRequest(
                "$baseUrl$apiPrefix/vms/$vmId/snapshots/$snapshotId/revert",
                "POST"
            )
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                Logger.i(TAG, "VM $vmId reverted to snapshot $snapshotId successfully")
                true
            } else {
                handleApiError(response)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error reverting to snapshot $snapshotId: ${e.message}", e)
            false
        }
    }
    
    // ========================================
    // Backup Job Management APIs
    // ========================================
    
    /**
     * List all backup jobs
     * 
     * GET /rest/v0/backup/jobs
     */
    suspend fun listBackupJobs(): List<XoBackupJob> = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching backup jobs")

            // Without ?fields= the collection endpoint returns href strings, not objects.
            val fields = "id,name,mode,enabled,schedule,vms"
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/backup/jobs?fields=$fields")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val jsonArray = JSONArray(body)
                    val jobs = mutableListOf<XoBackupJob>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val jobJson = jsonArray.getJSONObject(i)
                        
                        val job = XoBackupJob(
                            id = jobJson.getString("id"),
                            name = jobJson.getString("name"),
                            mode = jobJson.getString("mode"),
                            enabled = jobJson.getBoolean("enabled"),
                            schedule = jobJson.optString("schedule").takeIf { it.isNotEmpty() },
                            vms = parseJsonArray(jobJson.optJSONArray("vms"))
                        )
                        
                        jobs.add(job)
                    }
                    
                    Logger.i(TAG, "Retrieved ${jobs.size} backup jobs")
                    jobs
                } else {
                    Logger.e(TAG, "Empty response when listing backup jobs")
                    emptyList()
                }
            } else {
                handleApiError(response)
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error listing backup jobs: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get backup job details
     * 
     * GET /rest/v0/backup/jobs/:id
     */
    suspend fun getBackupJob(jobId: String): XoBackupJob? = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching backup job details: $jobId")
            
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/backup/jobs/$jobId")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val jobJson = JSONObject(body)
                    
                    XoBackupJob(
                        id = jobJson.getString("id"),
                        name = jobJson.getString("name"),
                        mode = jobJson.getString("mode"),
                        enabled = jobJson.getBoolean("enabled"),
                        schedule = jobJson.optString("schedule").takeIf { it.isNotEmpty() },
                        vms = parseJsonArray(jobJson.optJSONArray("vms"))
                    )
                } else {
                    null
                }
            } else {
                handleApiError(response)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting backup job $jobId: ${e.message}", e)
            null
        }
    }
    
    /**
     * Trigger a backup job manually
     * 
     * POST /rest/v0/backup/jobs/:id/run
     */
    suspend fun triggerBackup(jobId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Triggering backup job: $jobId")
            
            val request = buildAuthenticatedRequest(
                "$baseUrl$apiPrefix/backup/jobs/$jobId/run",
                "POST"
            )
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                Logger.i(TAG, "Backup job $jobId triggered successfully")
                true
            } else {
                handleApiError(response)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error triggering backup job $jobId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get backup run history for a job
     * 
     * GET /rest/v0/backup/jobs/:id/runs
     */
    suspend fun getBackupRuns(jobId: String): List<XoBackupRun> = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching backup run history for job: $jobId")

            // Without ?fields= the collection endpoint returns href strings, not objects.
            val fields = "id,jobId,status,start,end,result"
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/backup/jobs/$jobId/runs?fields=$fields")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val jsonArray = JSONArray(body)
                    val runs = mutableListOf<XoBackupRun>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val runJson = jsonArray.getJSONObject(i)
                        
                        val run = XoBackupRun(
                            id = runJson.getString("id"),
                            jobId = runJson.getString("jobId"),
                            status = runJson.getString("status"),
                            start = runJson.getLong("start"),
                            end = runJson.optLong("end").takeIf { it != 0L },
                            result = runJson.optString("result").takeIf { it.isNotEmpty() }
                        )
                        
                        runs.add(run)
                    }
                    
                    Logger.i(TAG, "Retrieved ${runs.size} backup runs for job $jobId")
                    runs
                } else {
                    Logger.e(TAG, "Empty response when listing backup runs")
                    emptyList()
                }
            } else {
                handleApiError(response)
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting backup runs for job $jobId: ${e.message}", e)
            emptyList()
        }
    }
    
    // ========================================
    // Pool & Host Management APIs
    // ========================================
    
    /**
     * List all resource pools
     * 
     * GET /rest/v0/pools
     */
    suspend fun listPools(): List<XoPool> = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching resource pools")

            // Without ?fields= the collection endpoint returns href strings, not objects.
            val fields = "id,uuid,name_label,name_description,master,default_SR"
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/pools?fields=$fields")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val jsonArray = JSONArray(body)
                    val pools = mutableListOf<XoPool>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val poolJson = jsonArray.getJSONObject(i)
                        
                        // XO calls the pool description name_description.
                        val pool = XoPool(
                            id = poolJson.getString("id"),
                            uuid = poolJson.getString("uuid"),
                            name_label = poolJson.getString("name_label"),
                            description = poolJson.optString("name_description").takeIf { it.isNotEmpty() },
                            master = poolJson.getString("master"),
                            default_SR = poolJson.optString("default_SR").takeIf { it.isNotEmpty() }
                        )
                        
                        pools.add(pool)
                    }
                    
                    Logger.i(TAG, "Retrieved ${pools.size} resource pools")
                    pools
                } else {
                    Logger.e(TAG, "Empty response when listing pools")
                    emptyList()
                }
            } else {
                handleApiError(response)
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error listing pools: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get pool details
     * 
     * GET /rest/v0/pools/:id
     */
    suspend fun getPool(poolId: String): XoPool? = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching pool details: $poolId")
            
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/pools/$poolId")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val poolJson = JSONObject(body)
                    
                    // XO calls the pool description name_description.
                    XoPool(
                        id = poolJson.getString("id"),
                        uuid = poolJson.getString("uuid"),
                        name_label = poolJson.getString("name_label"),
                        description = poolJson.optString("name_description").takeIf { it.isNotEmpty() },
                        master = poolJson.getString("master"),
                        default_SR = poolJson.optString("default_SR").takeIf { it.isNotEmpty() }
                    )
                } else {
                    null
                }
            } else {
                handleApiError(response)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting pool $poolId: ${e.message}", e)
            null
        }
    }
    
    /**
     * List all hosts (optionally filtered by pool)
     * 
     * GET /rest/v0/hosts
     */
    suspend fun listHosts(poolId: String? = null): List<XoHost> = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching hosts" + (poolId?.let { " for pool $it" } ?: ""))

            // Without ?fields= the collection endpoint returns href strings, not objects.
            val fields = "id,uuid,name_label,hostname,memory,enabled,\$pool"
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/hosts?fields=$fields")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val jsonArray = JSONArray(body)
                    val hosts = mutableListOf<XoHost>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val hostJson = jsonArray.getJSONObject(i)
                        
                        // Filter by pool if specified
                        if (poolId != null) {
                            val hostPool = hostJson.optString("\$pool")
                            if (hostPool != poolId) {
                                continue
                            }
                        }
                        
                        // XO serves host memory as {usage,size}; free = size - usage.
                        val mem = hostJson.optJSONObject("memory")
                        val host = XoHost(
                            id = hostJson.getString("id"),
                            uuid = hostJson.getString("uuid"),
                            name_label = hostJson.getString("name_label"),
                            hostname = hostJson.getString("hostname"),
                            memory_total = mem?.optLong("size") ?: 0L,
                            memory_free = mem?.let { it.optLong("size") - it.optLong("usage") } ?: 0L,
                            enabled = hostJson.getBoolean("enabled"),
                            `$pool` = hostJson.optString("\$pool").takeIf { it.isNotEmpty() }
                        )
                        
                        hosts.add(host)
                    }
                    
                    Logger.i(TAG, "Retrieved ${hosts.size} hosts")
                    hosts
                } else {
                    Logger.e(TAG, "Empty response when listing hosts")
                    emptyList()
                }
            } else {
                handleApiError(response)
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error listing hosts: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get host details
     * 
     * GET /rest/v0/hosts/:id
     */
    suspend fun getHost(hostId: String): XoHost? = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching host details: $hostId")
            
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/hosts/$hostId")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val hostJson = JSONObject(body)
                    
                    // XO serves host memory as {usage,size}; free = size - usage.
                    val mem = hostJson.optJSONObject("memory")
                    XoHost(
                        id = hostJson.getString("id"),
                        uuid = hostJson.getString("uuid"),
                        name_label = hostJson.getString("name_label"),
                        hostname = hostJson.getString("hostname"),
                        memory_total = mem?.optLong("size") ?: 0L,
                        memory_free = mem?.let { it.optLong("size") - it.optLong("usage") } ?: 0L,
                        enabled = hostJson.getBoolean("enabled"),
                        `$pool` = hostJson.optString("\$pool").takeIf { it.isNotEmpty() }
                    )
                } else {
                    null
                }
            } else {
                handleApiError(response)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting host $hostId: ${e.message}", e)
            null
        }
    }
    
    /**
     * Get host statistics
     * 
     * GET /rest/v0/hosts/:id/stats
     * Returns: Map of stat name to value
     */
    suspend fun getHostStats(hostId: String): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Fetching host stats: $hostId")
            
            val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/hosts/$hostId/stats")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val statsJson = JSONObject(body)
                    val stats = mutableMapOf<String, Any>()
                    
                    statsJson.keys().forEach { key ->
                        when (val value = statsJson.get(key)) {
                            is Number -> stats[key] = value
                            is String -> stats[key] = value
                            is Boolean -> stats[key] = value
                            is JSONObject -> stats[key] = value.toString()
                            is JSONArray -> stats[key] = value.toString()
                        }
                    }
                    
                    stats
                } else {
                    null
                }
            } else {
                handleApiError(response)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting host stats for $hostId: ${e.message}", e)
            null
        }
    }
    
    /**
     * Get current auth token for external use (e.g., console connections)
     */
    fun getAuthToken(): String? = authToken

    /**
     * Get WebSocket URL for VM console
     * Xen Orchestra provides console access via WebSocket
     *
     * @param vmId VM UUID
     * @return WebSocket URL for console, or null on failure
     */
    suspend fun getConsoleWebSocketUrl(vmId: String): String? = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Getting console URL for VM: $vmId")

            // XO console URL format: wss://host:port/api/consoles/{vmId}
            // Or via REST API: GET /rest/v0/vms/{vmId}/console which returns console info

            // Try REST API first to get console details
            try {
                val request = buildAuthenticatedRequest("$baseUrl$apiPrefix/vms/$vmId/console")
                // use{}: on the non-success branch the body was never consumed or
                // closed, so the connection stayed checked out of the pool.
                val consoleUrl = executeRequest(request).use { response ->
                    if (!response.isSuccessful) null
                    else response.body?.string()?.let { JSONObject(it).optString("url") }
                }

                if (!consoleUrl.isNullOrEmpty()) {
                    if (!isAcceptableConsoleUrl(consoleUrl, host)) {
                        // Falling through to the constructed URL keeps the token
                        // pointed at the host the user actually configured.
                        Logger.w(TAG, "Ignoring off-host or non-WebSocket console URL from XO: ${Logger.urlForLogging(consoleUrl)}")
                    } else {
                        // Log scheme+host+path only — the query string
                        // carries a live console session credential.
                        Logger.i(TAG, "Got console URL from API: ${Logger.urlForLogging(consoleUrl)}")
                        return@withContext consoleUrl
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.d(TAG, "Console API endpoint not available: ${e.message}")
            }

            // Fallback: construct WebSocket URL directly.
            // xo-server registers the console proxy at /api/consoles/ (plural).
            val wsUrl = "wss://$host:$port/api/consoles/$vmId"
            Logger.i(TAG, "Using constructed console URL: ${Logger.urlForLogging(wsUrl)}")
            wsUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // API endpoint might not exist, use fallback
            Logger.d(TAG, "Console API not available, using fallback URL")
            val wsUrl = "wss://$host:$port/api/consoles/$vmId"
            Logger.i(TAG, "Fallback console URL: ${Logger.urlForLogging(wsUrl)}")
            wsUrl
        }
    }
}
