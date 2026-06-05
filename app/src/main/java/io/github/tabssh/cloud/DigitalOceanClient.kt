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
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ImportCandidate>()
        // Follow DO's cursor-based pagination via links.pages.next until exhausted.
        var url: String? = "https://api.digitalocean.com/v2/droplets?per_page=200"
        while (url != null) {
            val root = doGet(url, bearerToken)
            val droplets = root.optJSONArray("droplets") ?: break
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
            url = root.optJSONObject("links")?.optJSONObject("pages")
                ?.optString("next")?.takeIf { it.isNotBlank() }
        }
        Logger.i("DigitalOceanClient", "Fetched ${out.size} droplets for account=$accountName")
        out
    }

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<CloudInstanceState>()
            var url: String? = "https://api.digitalocean.com/v2/droplets?per_page=200"
            while (url != null) {
                val root = doGet(url, bearerToken)
                val droplets = root.optJSONArray("droplets") ?: break
                for (i in 0 until droplets.length()) {
                    val d = droplets.optJSONObject(i) ?: continue
                    val rawStatus = d.optString("status", "unknown")
                    // DO statuses: new → starting, active → running, off/archive → stopped
                    val normStatus = when (rawStatus) {
                        "active" -> "running"
                        "off", "archive" -> "stopped"
                        "new" -> "starting"
                        else -> "unknown"
                    }
                    val region = d.optJSONObject("region")?.optString("slug")
                    val publicIp = pickAddress(d)
                    out += CloudInstanceState(
                        id = d.optInt("id", 0).toString(),
                        name = d.optString("name", "do-${d.optInt("id", 0)}"),
                        ip = publicIp,
                        privateIp = pickPrivateAddress(d),
                        status = normStatus,
                        rawStatus = rawStatus,
                        region = region
                    )
                }
                url = root.optJSONObject("links")?.optJSONObject("pages")
                    ?.optString("next")?.takeIf { it.isNotBlank() }
            }
            out
        }

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean =
        postDropletAction(bearerToken, instanceId, """{"type":"power_on"}""", 201)

    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        postDropletAction(bearerToken, instanceId, """{"type":"shutdown"}""", 201)

    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        postDropletAction(bearerToken, instanceId, """{"type":"reboot"}""", 201)

    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        postDropletAction(bearerToken, instanceId, """{"type":"power_cycle"}""", 201)

    private fun doGet(url: String, bearerToken: String): JSONObject {
        val req = Request.Builder()
            .url(url)
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
        return JSONObject(body)
    }

    private suspend fun postDropletAction(
        bearerToken: String,
        instanceId: String,
        jsonBody: String,
        expectedCode: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder()
            .url("https://api.digitalocean.com/v2/droplets/$instanceId/actions")
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            resp.code == expectedCode || resp.isSuccessful
        }
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

    /** Extract first private v4 address for reference. */
    private fun pickPrivateAddress(droplet: JSONObject): String? {
        val networks = droplet.optJSONObject("networks") ?: return null
        networks.optJSONArray("v4")?.let { v4 ->
            for (i in 0 until v4.length()) {
                val n = v4.optJSONObject(i) ?: continue
                if (n.optString("type") == "private") {
                    val ip = n.optString("ip_address")
                    if (ip.isNotBlank()) return ip
                }
            }
        }
        return null
    }
}
