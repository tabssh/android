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
 * Scaleway Instances inventory.
 *
 * Endpoint: https://api.scaleway.com/instance/v1/zones/{zone}/servers
 * Auth:     X-Auth-Token: <secret-key>   (NOT Authorization: Bearer)
 *
 * Scaleway is zoned — a token only sees the zone you query — so we iterate
 * every public zone and merge the results. A 404/empty zone just yields no
 * servers for that zone and is not an error.
 *
 * The canonical public IPv4 is an entry in the `public_ips` array whose
 * `family` is "inet"; the singular `public_ip` is its deprecated predecessor
 * and is still populated on older Instances, so we read both.
 * Pagination is plain `?page=N&per_page=100`; the response has no `next`
 * cursor, so we stop when a page returns fewer than `per_page` entries.
 */
class ScalewayClient : CloudProvider {

    override val type = CloudProviderType.SCALEWAY

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
        val out = mutableListOf<ImportCandidate>()
        for (zone in ZONES) {
            var page = 1
            while (true) {
                val root = doGet(
                    "https://api.scaleway.com/instance/v1/zones/$zone/servers?per_page=100&page=$page",
                    bearerToken
                ) ?: break
                val servers = root.optJSONArray("servers") ?: break
                for (i in 0 until servers.length()) {
                    val s = servers.optJSONObject(i) ?: continue
                    val name = s.optString("name", "scaleway-${s.optString("id", "?")}")
                    val ip = publicV4(s)
                    if (ip.isNullOrBlank()) {
                        Logger.d("ScalewayClient", "Server $name has no public v4 — skipping")
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
                            advancedSettings = """{"cloud_source":"scaleway:$accountName","cloud_region":"$zone"}""",
                            createdAt = System.currentTimeMillis()
                        ),
                        sourceLabel = "Scaleway / $zone"
                    )
                }
                if (servers.length() < 100) break
                page++
            }
        }
        Logger.i("ScalewayClient", "Fetched ${out.size} Scaleway servers for account=$accountName")
        out
    }

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<CloudInstanceState>()
            for (zone in ZONES) {
                var page = 1
                while (true) {
                    val root = doGet(
                        "https://api.scaleway.com/instance/v1/zones/$zone/servers?per_page=100&page=$page",
                        bearerToken
                    ) ?: break
                    val servers = root.optJSONArray("servers") ?: break
                    for (i in 0 until servers.length()) {
                        val s = servers.optJSONObject(i) ?: continue
                        val rawStatus = s.optString("state", "unknown")
                        val normStatus = when (rawStatus) {
                            "running" -> "running"
                            "stopped" -> "stopped"
                            "starting" -> "starting"
                            "stopping" -> "stopping"
                            else -> CloudInstanceState.normalizeStatus(rawStatus)
                        }
                        out += CloudInstanceState(
                            id = s.optString("id", ""),
                            name = s.optString("name", "scaleway-${s.optString("id", "?")}"),
                            ip = publicV4(s),
                            privateIp = s.optString("private_ip", "").ifBlank { null },
                            status = normStatus,
                            rawStatus = rawStatus,
                            region = zone
                        )
                    }
                    if (servers.length() < 100) break
                    page++
                }
            }
            out
        }

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(bearerToken, instanceId, "poweron")

    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(bearerToken, instanceId, "poweroff")

    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(bearerToken, instanceId, "reboot")

    /**
     * The Instance API exposes no hard power cycle — the closest actions are
     * `reboot` (stop and restart) and `stop_in_place`. A hard cycle would mean
     * `poweroff` then `poweron`, but `poweroff` is asynchronous and a `poweron`
     * against a still-stopping instance is rejected with a precondition error,
     * so we use `reboot` and let the provider sequence it.
     */
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(bearerToken, instanceId, "reboot")

    /**
     * Returns null when the zone is simply empty/unavailable (404), which is
     * normal — a token rarely has servers in every zone. Throws on auth and
     * hard failures so the caller can surface them.
     */
    /**
     * First public IPv4 on the Instance, preferring the `public_ips` array
     * (`family` "inet") and falling back to the deprecated singular
     * `public_ip` object that older Instances still carry.
     */
    private fun publicV4(server: JSONObject): String? {
        val ips = server.optJSONArray("public_ips")
        if (ips != null) {
            for (i in 0 until ips.length()) {
                val ip = ips.optJSONObject(i) ?: continue
                if (ip.optString("family") == "inet") {
                    val addr = ip.optString("address")
                    if (addr.isNotBlank()) return addr
                }
            }
        }
        return server.optJSONObject("public_ip")?.optString("address")?.ifBlank { null }
    }

    private fun doGet(url: String, bearerToken: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("X-Auth-Token", bearerToken)
            .header("Accept", "application/json")
            .get()
            .build()
        val body = http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) {
                    throw CloudAuthException("Scaleway token rejected (HTTP ${resp.code})")
                }
                throw IllegalStateException("Scaleway API HTTP ${resp.code}: ${resp.message}")
            }
            resp.body?.string().orEmpty()
        }
        return JSONObject(body)
    }

    private suspend fun postAction(
        bearerToken: String,
        instanceId: String,
        action: String
    ): Boolean = withContext(Dispatchers.IO) {
        // The action endpoint is zoned; the instance id carries no zone, so we
        // try each zone until one accepts the action (200). A 404 means the
        // instance isn't in that zone — keep looking.
        val body = """{"action":"$action"}""".toRequestBody("application/json".toMediaTypeOrNull())
        for (zone in ZONES) {
            val req = Request.Builder()
                .url("https://api.scaleway.com/instance/v1/zones/$zone/servers/$instanceId/action")
                .header("X-Auth-Token", bearerToken)
                .header("Accept", "application/json")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw CloudAuthException("Scaleway token rejected (HTTP ${resp.code})")
                }
                if (resp.code == 404) return@use
                if (resp.isSuccessful) return@withContext true
            }
        }
        false
    }

    companion object {
        // All public Scaleway Instance zones as of writing; empty zones are
        // skipped at runtime so a stale entry here is harmless.
        private val ZONES = listOf(
            "fr-par-1", "fr-par-2", "fr-par-3",
            "nl-ams-1", "nl-ams-2", "nl-ams-3",
            "pl-waw-1", "pl-waw-2", "pl-waw-3",
            "it-mil-1"
        )
    }
}
