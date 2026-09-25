package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * UpCloud server inventory.
 *
 * Endpoint: https://api.upcloud.com/1.3/server
 * Auth:     HTTP Basic — an API username + password created in the UpCloud
 *           Hub. We take the credential as "user:password" in the single
 *           token field (same convention as AWS's AKID:SECRET:REGION) and
 *           let OkHttp build the Basic header.
 *
 * `GET /1.3/server` returns every server with its `ip_addresses` inline, so
 * no per-server detail call is needed. The public IPv4 is the entry whose
 * `access` is "public" and `family` is "IPv4".
 */
class UpCloudClient : CloudProvider {

    override val type = CloudProviderType.UPCLOUD

    private val http: OkHttpClient = io.github.tabssh.network.SharedHttpClient.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val auth = basicAuth(bearerToken)
        val root = doGet("https://api.upcloud.com/1.3/server", auth)
        val servers = root.optJSONObject("servers")?.optJSONArray("server")
            ?: return@withContext emptyList()
        val out = mutableListOf<ImportCandidate>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val title = s.optString("title", "upcloud-${s.optString("uuid", "?")}")
            val zone = s.optString("zone", "")
            val ip = pickPublicV4(s)
            if (ip.isNullOrBlank()) {
                Logger.d("UpCloudClient", "Server $title has no public v4 — skipping")
                continue
            }
            out += ImportCandidate(
                profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = title,
                    host = ip,
                    port = 22,
                    username = "root",
                    authType = "password",
                    advancedSettings = cloudAdvancedSettings(
                        "cloud_source" to "upcloud:$accountName",
                        "cloud_region" to zone
                    ),
                    createdAt = System.currentTimeMillis()
                ),
                sourceLabel = "UpCloud / ${zone.ifBlank { "?" }}"
            )
        }
        Logger.i("UpCloudClient", "Fetched ${out.size} UpCloud servers for account=$accountName")
        out
    }

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> =
        withContext(Dispatchers.IO) {
            val auth = basicAuth(bearerToken)
            val root = doGet("https://api.upcloud.com/1.3/server", auth)
            val servers = root.optJSONObject("servers")?.optJSONArray("server")
                ?: return@withContext emptyList()
            val out = mutableListOf<CloudInstanceState>()
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                val rawStatus = s.optString("state", "unknown")
                val normStatus = when (rawStatus) {
                    "started" -> "running"
                    "stopped" -> "stopped"
                    else -> CloudInstanceState.normalizeStatus(rawStatus)
                }
                out += CloudInstanceState(
                    id = s.optString("uuid", ""),
                    name = s.optString("title", "upcloud-${s.optString("uuid", "?")}"),
                    ip = pickPublicV4(s),
                    privateIp = pickPrivateV4(s),
                    status = normStatus,
                    rawStatus = rawStatus,
                    region = s.optString("zone", "").ifBlank { null }
                )
            }
            out
        }

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(bearerToken, "/1.3/server/$instanceId/start", "{}")

    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(
            bearerToken,
            "/1.3/server/$instanceId/stop",
            """{"stop_server":{"stop_type":"soft","timeout":"60"}}"""
        )

    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(
            bearerToken,
            "/1.3/server/$instanceId/restart",
            """{"restart_server":{"stop_type":"soft","timeout":"60"}}"""
        )

    /** UpCloud hard stop = immediate power cut, used as the force restart. */
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(
            bearerToken,
            "/1.3/server/$instanceId/restart",
            """{"restart_server":{"stop_type":"hard","timeout":"30","timeout_action":"destroy"}}"""
        )

    private fun basicAuth(bearerToken: String): String {
        val parts = bearerToken.split(':', limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank()) {
            "UpCloud credential must be api_username:api_password"
        }
        return Credentials.basic(parts[0].trim(), parts[1].trim())
    }

    private fun doGet(url: String, auth: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .get()
            .build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) {
                    throw CloudAuthException("UpCloud credentials rejected (HTTP ${resp.code})")
                }
                throw IllegalStateException("UpCloud API HTTP ${resp.code}: ${resp.message}")
            }
            resp.body?.string().orEmpty()
        }
        return JSONObject(body)
    }

    private suspend fun postAction(
        bearerToken: String,
        path: String,
        jsonBody: String
    ): Boolean = withContext(Dispatchers.IO) {
        val auth = basicAuth(bearerToken)
        val body = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder()
            .url("https://api.upcloud.com$path")
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw CloudAuthException("UpCloud credentials rejected (HTTP ${resp.code})")
            }
            resp.isSuccessful
        }
    }

    private fun pickPublicV4(server: JSONObject): String? = pickIp(server, "public", "IPv4")

    private fun pickPrivateV4(server: JSONObject): String? = pickIp(server, "private", "IPv4")

    private fun pickIp(server: JSONObject, access: String, family: String): String? {
        val arr = server.optJSONObject("ip_addresses")?.optJSONArray("ip_address") ?: return null
        for (i in 0 until arr.length()) {
            val ip = arr.optJSONObject(i) ?: continue
            if (ip.optString("access") == access && ip.optString("family") == family) {
                return ip.optString("address").ifBlank { null }
            }
        }
        return null
    }
}
