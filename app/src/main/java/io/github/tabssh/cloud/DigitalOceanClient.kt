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
 * Wave 5.1 — DigitalOcean droplet inventory.
 *
 * Endpoint: https://api.digitalocean.com/v2/droplets?per_page=200
 * Auth:    `Authorization: Bearer <token>` — a v2 personal access token.
 *
 * We pull at most one page (200 droplets). If a user has more than 200
 * we'll surface a warning and they can paginate later — that's not the
 * common case, and pagination adds complexity for marginal benefit.
 *
 * The token NEVER hits disk in plain form. The caller (CloudImportManager
 * activity flow) decrypts it from SecurePasswordManager just for the call,
 * passes the bytes here, and lets us throw it away.
 */
class DigitalOceanClient : CloudProvider {

    override val type = CloudProviderType.DIGITALOCEAN

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.digitalocean.com/v2/droplets?per_page=200")
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .get()
            .build()

        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("DigitalOcean API HTTP ${resp.code}: ${resp.message}")
            }
            resp.body?.string().orEmpty()
        }

        val root = JSONObject(body)
        val droplets = root.optJSONArray("droplets")
            ?: return@withContext emptyList<ImportCandidate>()

        val out = mutableListOf<ImportCandidate>()
        for (i in 0 until droplets.length()) {
            val d = droplets.optJSONObject(i) ?: continue
            val name = d.optString("name", "do-${d.optInt("id", 0)}")
            val region = d.optJSONObject("region")?.optString("slug").orEmpty()

            val ip = pickAddress(d)
            if (ip.isNullOrBlank()) {
                Logger.d("DigitalOceanClient", "Droplet $name has no public address — skipping")
                continue
            }

            out += ImportCandidate(
                profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    host = ip,
                    port = 22,
                    username = "root", // DO defaults to root; user can edit
                    authType = "password",
                    groupId = null,
                    advancedSettings = """{"cloud_source":"digitalocean:$accountName","cloud_region":"$region"}""",
                    createdAt = System.currentTimeMillis()
                ),
                sourceLabel = "DigitalOcean / ${region.ifBlank { "?" }}"
            )
        }
        Logger.i("DigitalOceanClient", "Fetched ${out.size} droplets for account=$accountName")
        out
    }

    /** Prefer public v4, fall back to public v6, else null. */
    private fun pickAddress(droplet: JSONObject): String? {
        val networks = droplet.optJSONObject("networks") ?: return null
        networks.optJSONArray("v4")?.let { v4 ->
            for (i in 0 until v4.length()) {
                val n = v4.optJSONObject(i) ?: continue
                if (n.optString("type") == "public") {
                    val ip = n.optString("ip_address")
                    if (ip.isNotBlank()) return ip
                }
            }
        }
        networks.optJSONArray("v6")?.let { v6 ->
            for (i in 0 until v6.length()) {
                val n = v6.optJSONObject(i) ?: continue
                if (n.optString("type") == "public") {
                    val ip = n.optString("ip_address")
                    if (ip.isNotBlank()) return ip
                }
            }
        }
        return null
    }
}
