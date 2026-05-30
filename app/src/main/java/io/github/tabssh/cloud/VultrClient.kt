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
 * Wave 5.2 — Vultr instance inventory.
 *
 * Endpoint: https://api.vultr.com/v2/instances?per_page=200
 * Auth:     Authorization: Bearer <api-key>
 *
 * `main_ip` is the public IPv4 (string); `region` is a slug like `nyc`.
 */
class VultrClient : CloudProvider {

    override val type = CloudProviderType.VULTR

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.vultr.com/v2/instances?per_page=200")
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .get()
            .build()

        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Vultr API HTTP ${resp.code}: ${resp.message}")
            }
            resp.body?.string().orEmpty()
        }

        val root = JSONObject(body)
        val instances = root.optJSONArray("instances")
            ?: return@withContext emptyList<ImportCandidate>()

        val out = mutableListOf<ImportCandidate>()
        for (i in 0 until instances.length()) {
            val inst = instances.optJSONObject(i) ?: continue
            val label = inst.optString("label").ifBlank { inst.optString("hostname", "vultr-${inst.optString("id", "?")}") }
            val region = inst.optString("region", "")
            val mainIp = inst.optString("main_ip", "")
            if (mainIp.isBlank() || mainIp == "0.0.0.0") {
                Logger.d("VultrClient", "Instance $label has no main_ip — skipping")
                continue
            }
            out += ImportCandidate(
                profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = label,
                    host = mainIp,
                    port = 22,
                    username = "root",
                    authType = "password",
                    advancedSettings = """{"cloud_source":"vultr:$accountName","cloud_region":"$region"}""",
                    createdAt = System.currentTimeMillis()
                ),
                sourceLabel = "Vultr / ${region.ifBlank { "?" }}"
            )
        }
        Logger.i("VultrClient", "Fetched ${out.size} Vultr instances for account=$accountName")
        out
    }

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://api.vultr.com/v2/instances?per_page=200")
                .header("Authorization", "Bearer $bearerToken")
                .header("Accept", "application/json")
                .get()
                .build()

            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Vultr API HTTP ${resp.code}: ${resp.message}")
                }
                resp.body?.string().orEmpty()
            }

            val root = JSONObject(body)
            val instances = root.optJSONArray("instances")
                ?: return@withContext emptyList<CloudInstanceState>()

            val out = mutableListOf<CloudInstanceState>()
            for (i in 0 until instances.length()) {
                val inst = instances.optJSONObject(i) ?: continue
                // Vultr uses power_status for running/stopped
                val rawStatus = inst.optString("power_status",
                    inst.optString("status", "unknown"))
                val normStatus = CloudInstanceState.normalizeStatus(rawStatus)
                val mainIp = inst.optString("main_ip", "").ifBlank { null }
                    ?.takeIf { it != "0.0.0.0" }
                val region = inst.optString("region", "").ifBlank { null }
                out += CloudInstanceState(
                    id = inst.optString("id", ""),
                    name = inst.optString("label").ifBlank {
                        inst.optString("hostname", "vultr-${inst.optString("id", "?")}")
                    },
                    ip = mainIp,
                    privateIp = inst.optString("internal_ip", "").ifBlank { null },
                    status = normStatus,
                    rawStatus = rawStatus,
                    region = region
                )
            }
            out
        }

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean =
        postVultrAction(bearerToken, "/v2/instances/$instanceId/start")

    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        postVultrAction(bearerToken, "/v2/instances/$instanceId/halt")

    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        postVultrAction(bearerToken, "/v2/instances/$instanceId/reboot")

    /** Vultr has no separate hard reset — same reboot endpoint. */
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        postVultrAction(bearerToken, "/v2/instances/$instanceId/reboot")

    private suspend fun postVultrAction(bearerToken: String, path: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = "{}".toRequestBody("application/json".toMediaTypeOrNull())
            val req = Request.Builder()
                .url("https://api.vultr.com$path")
                .header("Authorization", "Bearer $bearerToken")
                .header("Accept", "application/json")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                resp.code == 204 || resp.isSuccessful
            }
        }
}
