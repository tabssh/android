package io.github.tabssh.hypervisor.xcpng

import io.github.tabssh.utils.logging.Logger
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
 * Xen Orchestra REST API Client
 * 
 * Supports Xen Orchestra (XO) server connections via REST API
 * Provides full VM management, snapshots, backups, and resource pool operations
 * 
 * API Documentation: https://xen-orchestra.com/docs/rest_api.html
 */
class XenOrchestraApiClient(
    private val host: String,
    private val port: Int = 443,
    private val email: String,
    private val password: String,
    private val verifySsl: Boolean = false
) {
    
    private val baseUrl = "https://$host:$port"
    private val client: OkHttpClient
    
    // Authentication state
    private var authToken: String? = null
    private var userId: String? = null
    private var tokenExpiresAt: Long? = null
    
    // OAuth2 state (if supported by XO)
    private var refreshToken: String? = null
    
    companion object {
        private const val TAG = "XenOrchestraAPI"
        private const val API_PREFIX = "/rest/v0"
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
        val builder = OkHttpClient.Builder()
        
        if (!verifySsl) {
            // Trust all certificates (for self-signed certs)
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
        
        Logger.d(TAG, "XenOrchestraApiClient initialized for $baseUrl")
    }
    
    /**
     * Authentication - Basic Auth with email/password
     * 
     * POST /rest/v0/users/signin
     * Body: {"email": "user@example.com", "password": "..."}
     * Response: {"token": "...", "userId": "..."}
     */
    suspend fun authenticate(): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Attempting authentication to $baseUrl")
            
            val json = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
            
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl$API_PREFIX/users/signin")
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val responseJson = JSONObject(responseBody)
                    
                    authToken = responseJson.optString("token")
                    userId = responseJson.optString("userId")
                    
                    // Check if token has expiry (some XO versions may include this)
                    if (responseJson.has("expiresAt")) {
                        tokenExpiresAt = responseJson.getLong("expiresAt")
                    }
                    
                    Logger.i(TAG, "Authentication successful, userId: $userId")
                    Logger.d(TAG, "Auth token: ${authToken?.take(20)}...")
                    
                    true
                } else {
                    Logger.e(TAG, "Authentication failed: Empty response body")
                    false
                }
            } else {
                val errorBody = response.body?.string()
                Logger.e(TAG, "Authentication failed: HTTP ${response.code} - $errorBody")
                false
            }
            
        } catch (e: IOException) {
            Logger.e(TAG, "Network error during authentication: ${e.message}", e)
            false
        } catch (e: Exception) {
            Logger.e(TAG, "Authentication error: ${e.message}", e)
            false
        }
    }
    
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
            builder.addHeader("Authorization", "Bearer $it")
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
                // Token expired, try to re-authenticate
                if (authenticate()) {
                    // Retry request with new token
                    val newRequest = buildAuthenticatedRequest(
                        request.url.toString(),
                        request.method,
                        request.body
                    )
                    return@withContext client.newCall(newRequest).execute()
                }
            }
            
            response
        } catch (e: IOException) {
            Logger.e(TAG, "Network error: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Parse JSON response to data object
     */
    private inline fun <reified T> parseJsonResponse(response: Response): T? {
        return try {
            val body = response.body?.string()
            if (body != null && response.isSuccessful) {
                // TODO: Implement JSON parsing based on type T
                // For now, return null to allow compilation
                Logger.d(TAG, "Response: ${body.take(200)}")
                null
            } else {
                Logger.e(TAG, "Failed to parse response: HTTP ${response.code}")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error parsing JSON: ${e.message}", e)
            null
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
            Logger.e(TAG, "Raw error body: $errorBody")
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/vms")
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
                        
                        // Parse VM object
                        val vm = XoVM(
                            id = vmJson.getString("id"),
                            uuid = vmJson.getString("uuid"),
                            name_label = vmJson.getString("name_label"),
                            power_state = vmJson.getString("power_state"),
                            memory = vmJson.getLong("memory"),
                            vcpus = vmJson.getInt("VCPUs_max"),
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/vms/$vmId")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val vmJson = JSONObject(body)
                    
                    XoVM(
                        id = vmJson.getString("id"),
                        uuid = vmJson.getString("uuid"),
                        name_label = vmJson.getString("name_label"),
                        power_state = vmJson.getString("power_state"),
                        memory = vmJson.getLong("memory"),
                        vcpus = vmJson.getInt("VCPUs_max"),
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
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting VM $vmId: ${e.message}", e)
            null
        }
    }
    
    /**
     * Start a VM
     * 
     * POST /rest/v0/vms/:id/start
     */
    suspend fun startVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Starting VM: $vmId")
            
            val request = buildAuthenticatedRequest(
                "$baseUrl$API_PREFIX/vms/$vmId/start",
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
        } catch (e: Exception) {
            Logger.e(TAG, "Error starting VM $vmId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Stop a VM (clean shutdown)
     * 
     * POST /rest/v0/vms/:id/stop
     * POST /rest/v0/vms/:id/force-stop (if force=true)
     */
    suspend fun stopVM(vmId: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val action = if (force) "force-stop" else "stop"
            Logger.d(TAG, "Stopping VM: $vmId (force=$force)")
            
            val request = buildAuthenticatedRequest(
                "$baseUrl$API_PREFIX/vms/$vmId/$action",
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
        } catch (e: Exception) {
            Logger.e(TAG, "Error stopping VM $vmId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Reboot a VM
     * 
     * POST /rest/v0/vms/:id/restart
     */
    suspend fun rebootVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Rebooting VM: $vmId")
            
            val request = buildAuthenticatedRequest(
                "$baseUrl$API_PREFIX/vms/$vmId/restart",
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
        } catch (e: Exception) {
            Logger.e(TAG, "Error rebooting VM $vmId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Suspend a VM
     * 
     * POST /rest/v0/vms/:id/suspend
     */
    suspend fun suspendVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Suspending VM: $vmId")
            
            val request = buildAuthenticatedRequest(
                "$baseUrl$API_PREFIX/vms/$vmId/suspend",
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
        } catch (e: Exception) {
            Logger.e(TAG, "Error suspending VM $vmId: ${e.message}", e)
            false
        }
    }
    
    /**
     * Resume a suspended VM
     * 
     * POST /rest/v0/vms/:id/resume
     */
    suspend fun resumeVM(vmId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Resuming VM: $vmId")
            
            val request = buildAuthenticatedRequest(
                "$baseUrl$API_PREFIX/vms/$vmId/resume",
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/vms/$vmId/snapshots")
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
                "$baseUrl$API_PREFIX/vms/$vmId/snapshots",
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
                "$baseUrl$API_PREFIX/vms/$vmId/snapshots/$snapshotId",
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
                "$baseUrl$API_PREFIX/vms/$vmId/snapshots/$snapshotId/revert",
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/backup/jobs")
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/backup/jobs/$jobId")
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
                "$baseUrl$API_PREFIX/backup/jobs/$jobId/run",
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/backup/jobs/$jobId/runs")
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/pools")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val jsonArray = JSONArray(body)
                    val pools = mutableListOf<XoPool>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val poolJson = jsonArray.getJSONObject(i)
                        
                        val pool = XoPool(
                            id = poolJson.getString("id"),
                            uuid = poolJson.getString("uuid"),
                            name_label = poolJson.getString("name_label"),
                            description = poolJson.optString("description").takeIf { it.isNotEmpty() },
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/pools/$poolId")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val poolJson = JSONObject(body)
                    
                    XoPool(
                        id = poolJson.getString("id"),
                        uuid = poolJson.getString("uuid"),
                        name_label = poolJson.getString("name_label"),
                        description = poolJson.optString("description").takeIf { it.isNotEmpty() },
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/hosts")
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
                        
                        val host = XoHost(
                            id = hostJson.getString("id"),
                            uuid = hostJson.getString("uuid"),
                            name_label = hostJson.getString("name_label"),
                            hostname = hostJson.getString("hostname"),
                            memory_total = hostJson.getLong("memory_total"),
                            memory_free = hostJson.getLong("memory_free"),
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/hosts/$hostId")
            val response = executeRequest(request)
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val hostJson = JSONObject(body)
                    
                    XoHost(
                        id = hostJson.getString("id"),
                        uuid = hostJson.getString("uuid"),
                        name_label = hostJson.getString("name_label"),
                        hostname = hostJson.getString("hostname"),
                        memory_total = hostJson.getLong("memory_total"),
                        memory_free = hostJson.getLong("memory_free"),
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
            
            val request = buildAuthenticatedRequest("$baseUrl$API_PREFIX/hosts/$hostId/stats")
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
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting host stats for $hostId: ${e.message}", e)
            null
        }
    }
    
    // ======================== WebSocket Real-Time Events ========================
    
    /**
     * WebSocket event types
     */
    data class XoEvent(
        val type: String,          // "vm.started", "vm.stopped", "vm.restarted", "snapshot.created", etc.
        val vmId: String?,         // VM UUID (if applicable)
        val timestamp: Long,       // Event timestamp
        val data: JSONObject?      // Additional event data
    )
    
    /**
     * WebSocket event listener interface
     */
    interface EventListener {
        fun onVMStateChanged(vmId: String, newState: String)
        fun onVMCreated(vmId: String)
        fun onVMDeleted(vmId: String)
        fun onSnapshotCreated(vmId: String, snapshotId: String)
        fun onSnapshotDeleted(vmId: String, snapshotId: String)
        fun onBackupCompleted(jobId: String, success: Boolean)
        fun onConnectionStateChanged(connected: Boolean)
        fun onError(error: String)
    }
    
    private var webSocket: WebSocket? = null
    private var eventListener: EventListener? = null
    private var isWebSocketConnected = false
    
    /**
     * Connect to WebSocket for real-time events
     * 
     * WebSocket URL: wss://host:port/api/
     * Sends auth token after connection
     */
    fun connectWebSocket(listener: EventListener) {
        this.eventListener = listener
        
        if (authToken == null) {
            Logger.e(TAG, "Cannot connect WebSocket: Not authenticated")
            listener.onError("Not authenticated")
            return
        }
        
        val wsUrl = "wss://$host:$port/api/"
        Logger.d(TAG, "Connecting WebSocket to $wsUrl")
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.i(TAG, "WebSocket connected")
                isWebSocketConnected = true
                
                // Send authentication
                val authMessage = JSONObject().apply {
                    put("type", "authenticate")
                    put("token", authToken)
                }
                webSocket.send(authMessage.toString())
                
                listener.onConnectionStateChanged(true)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Logger.d(TAG, "WebSocket message: $text")
                
                try {
                    val json = JSONObject(text)
                    val event = parseEvent(json)
                    
                    // Route event to appropriate listener method
                    when (event.type) {
                        "vm.started", "vm.running" -> {
                            event.vmId?.let { listener.onVMStateChanged(it, "Running") }
                        }
                        "vm.stopped", "vm.halted" -> {
                            event.vmId?.let { listener.onVMStateChanged(it, "Halted") }
                        }
                        "vm.suspended" -> {
                            event.vmId?.let { listener.onVMStateChanged(it, "Suspended") }
                        }
                        "vm.restarted" -> {
                            event.vmId?.let { listener.onVMStateChanged(it, "Running") }
                        }
                        "vm.created" -> {
                            event.vmId?.let { listener.onVMCreated(it) }
                        }
                        "vm.deleted" -> {
                            event.vmId?.let { listener.onVMDeleted(it) }
                        }
                        "snapshot.created" -> {
                            val vmId = event.data?.optString("vmId")
                            val snapshotId = event.data?.optString("snapshotId")
                            if (vmId != null && snapshotId != null) {
                                listener.onSnapshotCreated(vmId, snapshotId)
                            }
                        }
                        "snapshot.deleted" -> {
                            val vmId = event.data?.optString("vmId")
                            val snapshotId = event.data?.optString("snapshotId")
                            if (vmId != null && snapshotId != null) {
                                listener.onSnapshotDeleted(vmId, snapshotId)
                            }
                        }
                        "backup.completed" -> {
                            val jobId = event.data?.optString("jobId")
                            val success = event.data?.optBoolean("success") ?: false
                            if (jobId != null) {
                                listener.onBackupCompleted(jobId, success)
                            }
                        }
                        else -> {
                            Logger.d(TAG, "Unhandled event type: ${event.type}")
                        }
                    }
                    
                } catch (e: Exception) {
                    Logger.e(TAG, "Error parsing WebSocket message: ${e.message}", e)
                    listener.onError("Failed to parse event: ${e.message}")
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Logger.i(TAG, "WebSocket closing: $code - $reason")
                isWebSocketConnected = false
                listener.onConnectionStateChanged(false)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.e(TAG, "WebSocket failure: ${t.message}", t)
                isWebSocketConnected = false
                listener.onConnectionStateChanged(false)
                listener.onError("WebSocket connection failed: ${t.message}")
            }
        })
    }
    
    /**
     * Parse event JSON to XoEvent object
     */
    private fun parseEvent(json: JSONObject): XoEvent {
        return XoEvent(
            type = json.optString("type", "unknown"),
            vmId = json.optString("vmId").takeIf { it.isNotEmpty() },
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            data = json.optJSONObject("data")
        )
    }
    
    /**
     * Subscribe to specific VM events
     */
    fun subscribeToVM(vmId: String) {
        if (webSocket == null || !isWebSocketConnected) {
            Logger.w(TAG, "Cannot subscribe: WebSocket not connected")
            return
        }
        
        val message = JSONObject().apply {
            put("type", "subscribe")
            put("channel", "vm.$vmId")
        }
        
        webSocket?.send(message.toString())
        Logger.d(TAG, "Subscribed to VM: $vmId")
    }
    
    /**
     * Subscribe to all VMs
     */
    fun subscribeToAllVMs() {
        if (webSocket == null || !isWebSocketConnected) {
            Logger.w(TAG, "Cannot subscribe: WebSocket not connected")
            return
        }
        
        val message = JSONObject().apply {
            put("type", "subscribe")
            put("channel", "vm.*")
        }
        
        webSocket?.send(message.toString())
        Logger.d(TAG, "Subscribed to all VMs")
    }
    
    /**
     * Disconnect WebSocket
     */
    fun disconnectWebSocket() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isWebSocketConnected = false
        eventListener = null
        Logger.i(TAG, "WebSocket disconnected")
    }
    
    /**
     * Check if WebSocket is connected
     */
    fun isWebSocketConnected(): Boolean = isWebSocketConnected
}
