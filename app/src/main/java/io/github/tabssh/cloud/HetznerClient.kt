package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Wave 5.2 — Hetzner Cloud server inventory.
 *
 * Endpoint: https://api.hetzner.cloud/v1/servers?per_page=200
 * Auth:     Authorization: Bearer <api-token>
 *
 * `public_net.ipv4.ip` is the canonical address; `public_net.ipv6.ip`
 * exists only as a /64 prefix on Hetzner — useless for SSH without
 * picking a specific host inside the prefix — so we skip v6.
 */
class HetznerClient : CloudProvider {

    override val type = CloudProviderType.HETZNER

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.hetzner.cloud/v1/servers?per_page=200")
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .get()
            .build()

        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Hetzner API HTTP ${resp.code}: ${resp.message}")
            }
            resp.body?.string().orEmpty()
        }

        val root = JSONObject(body)
        val servers = root.optJSONArray("servers")
            ?: return@withContext emptyList<ImportCandidate>()

        val out = mutableListOf<ImportCandidate>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val name = s.optString("name", "hetzner-${s.optInt("id", 0)}")
            val location = s.optJSONObject("datacenter")?.optJSONObject("location")?.optString("name").orEmpty()
            val ip = s.optJSONObject("public_net")?.optJSONObject("ipv4")?.optString("ip")
            if (ip.isNullOrBlank()) {
                Logger.d("HetznerClient", "Server $name has no public v4 address — skipping")
                continue
            }
            out += ImportCandidate(
                profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    host = ip,
                    port = 22,
                    username = "root",
                    authType = "password",
                    advancedSettings = """{"cloud_source":"hetzner:$accountName","cloud_region":"$location"}""",
                    createdAt = System.currentTimeMillis()
                ),
                sourceLabel = "Hetzner / ${location.ifBlank { "?" }}"
            )
        }
        Logger.i("HetznerClient", "Fetched ${out.size} Hetzner servers for account=$accountName")
        out
    }

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://api.hetzner.cloud/v1/servers?per_page=200")
                .header("Authorization", "Bearer $bearerToken")
                .header("Accept", "application/json")
                .get()
                .build()

            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Hetzner API HTTP ${resp.code}: ${resp.message}")
                }
                resp.body?.string().orEmpty()
            }

            val root = JSONObject(body)
            val servers = root.optJSONArray("servers")
                ?: return@withContext emptyList<CloudInstanceState>()

            val out = mutableListOf<CloudInstanceState>()
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                val rawStatus = s.optString("status", "unknown")
                val normStatus = when (rawStatus) {
                    "running" -> "running"
                    "off" -> "stopped"
                    "starting" -> "starting"
                    "stopping" -> "stopping"
                    "restarting" -> "rebooting"
                    else -> "unknown"
                }
                val location = s.optJSONObject("datacenter")
                    ?.optJSONObject("location")?.optString("name")
                val publicIp = s.optJSONObject("public_net")?.optJSONObject("ipv4")?.optString("ip")
                out += CloudInstanceState(
                    id = s.optInt("id", 0).toString(),
                    name = s.optString("name", "hetzner-${s.optInt("id", 0)}"),
                    ip = publicIp?.ifBlank { null },
                    privateIp = null,
                    status = normStatus,
                    rawStatus = rawStatus,
                    region = location
                )
            }
            out
        }

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean =
        postServerAction(bearerToken, instanceId, "power_on")

    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        postServerAction(bearerToken, instanceId, "power_off")

    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        postServerAction(bearerToken, instanceId, "reboot")

    /** Hetzner reset = hard power cycle, equivalent to force restart. */
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        postServerAction(bearerToken, instanceId, "reset")

    private suspend fun postServerAction(
        bearerToken: String,
        instanceId: String,
        action: String
    ): Boolean = withContext(Dispatchers.IO) {
        val body = "{}".toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder()
            .url("https://api.hetzner.cloud/v1/servers/$instanceId/actions/$action")
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            resp.code == 201 || resp.isSuccessful
        }
    }
}
