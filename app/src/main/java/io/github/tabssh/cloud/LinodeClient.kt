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
 * Wave 5.2 — Linode (Akamai) instance inventory.
 *
 * Endpoint: https://api.linode.com/v4/linode/instances?page_size=200
 * Auth:     Authorization: Bearer <personal-access-token>
 *
 * Each instance's `ipv4` field is an array of strings; the first public
 * address is what we want. Linode also returns private 192.168.x and
 * 10.x ranges in the same array, which we skip.
 */
class LinodeClient : CloudProvider {

    override val type = CloudProviderType.LINODE

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.linode.com/v4/linode/instances?page_size=200")
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .get()
            .build()

        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Linode API HTTP ${resp.code}: ${resp.message}")
            }
            resp.body?.string().orEmpty()
        }

        val root = JSONObject(body)
        val data = root.optJSONArray("data")
            ?: return@withContext emptyList<ImportCandidate>()

        val out = mutableListOf<ImportCandidate>()
        for (i in 0 until data.length()) {
            val inst = data.optJSONObject(i) ?: continue
            val label = inst.optString("label", "linode-${inst.optInt("id", 0)}")
            val region = inst.optString("region", "")
            val publicV4 = pickPublicV4(inst)
            if (publicV4.isNullOrBlank()) {
                Logger.d("LinodeClient", "Instance $label has no public v4 — skipping")
                continue
            }
            out += ImportCandidate(
                profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = label,
                    host = publicV4,
                    port = 22,
                    username = "root",
                    authType = "password",
                    advancedSettings = """{"cloud_source":"linode:$accountName","cloud_region":"$region"}""",
                    createdAt = System.currentTimeMillis()
                ),
                sourceLabel = "Linode / ${region.ifBlank { "?" }}"
            )
        }
        Logger.i("LinodeClient", "Fetched ${out.size} Linode instances for account=$accountName")
        out
    }

    private fun pickPublicV4(instance: JSONObject): String? {
        val arr = instance.optJSONArray("ipv4") ?: return null
        for (i in 0 until arr.length()) {
            val ip = arr.optString(i, "")
            if (ip.isBlank()) continue
            if (isPrivateRange(ip)) continue
            return ip
        }
        return null
    }

    private fun isPrivateRange(ip: String): Boolean {
        return ip.startsWith("10.") ||
            ip.startsWith("192.168.") ||
            (ip.startsWith("172.") && ip.split('.').getOrNull(1)?.toIntOrNull() in 16..31)
    }
}
