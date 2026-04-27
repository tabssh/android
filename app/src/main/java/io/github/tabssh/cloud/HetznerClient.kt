package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
}
