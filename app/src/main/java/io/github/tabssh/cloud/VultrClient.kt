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
}
