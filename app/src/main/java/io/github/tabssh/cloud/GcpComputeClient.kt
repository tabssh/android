package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Wave 8.2 — GCP Compute Engine inventory.
 *
 * Auth flow (service-account JSON to access token):
 *  1. User pastes the entire service-account JSON as the cloud-account
 *     "token". We extract `client_email`, `private_key` (PEM), `project_id`.
 *  2. Build a JWT with `iss=client_email`, scope, aud, iat, exp (1h max).
 *  3. Sign header.payload with RS256 using the private key.
 *  4. POST grant_type=jwt-bearer to oauth2.googleapis.com/token; receive access_token.
 *  5. GET aggregated instances with Authorization: Bearer <access_token>.
 *
 * Power actions require compute read-write scope, so we request
 * https://www.googleapis.com/auth/compute when performing actions.
 *
 * Zone is stored in cachedInstances metadata["zone"] for power actions.
 */
class GcpComputeClient : CloudProvider {

    override val type = CloudProviderType.GCP

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Cached instances from the last fetchLiveInstances call; used to resolve zone for power actions. */
    private var cachedInstances: List<CloudInstanceState> = emptyList()

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val sa = try {
            JSONObject(bearerToken)
        } catch (e: Exception) {
            throw IllegalStateException("GCP token must be the full service-account JSON")
        }
        val clientEmail = sa.optString("client_email").ifBlank {
            throw IllegalStateException("Missing client_email in service-account JSON")
        }
        val privateKeyPem = sa.optString("private_key").ifBlank {
            throw IllegalStateException("Missing private_key in service-account JSON")
        }
        val projectId = sa.optString("project_id").ifBlank {
            throw IllegalStateException("Missing project_id in service-account JSON")
        }

        val accessToken = exchangeJwtForAccessToken(clientEmail, privateKeyPem,
            "https://www.googleapis.com/auth/compute.readonly")

        val out = mutableListOf<ImportCandidate>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("https://compute.googleapis.com/compute/v1/projects/$projectId/aggregated/instances?maxResults=500")
                if (pageToken != null) append("&pageToken=").append(URLEncoder.encode(pageToken, "UTF-8"))
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()
            val body = http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message") ?: resp.message
                    } catch (_: Exception) { resp.message }
                    throw IllegalStateException("GCP API HTTP ${resp.code}: $msg")
                }
                raw
            }
            out += parseInstances(body, projectId, accountName)
            pageToken = JSONObject(body).optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)

        Logger.i("GcpComputeClient", "Fetched ${out.size} GCE instances for project=$projectId account=$accountName")
        out
    }

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> =
        withContext(Dispatchers.IO) {
            val sa = try { JSONObject(bearerToken) } catch (e: Exception) {
                throw IllegalStateException("GCP token must be the full service-account JSON")
            }
            val clientEmail = sa.optString("client_email").ifBlank {
                throw IllegalStateException("Missing client_email in service-account JSON")
            }
            val privateKeyPem = sa.optString("private_key").ifBlank {
                throw IllegalStateException("Missing private_key in service-account JSON")
            }
            val projectId = sa.optString("project_id").ifBlank {
                throw IllegalStateException("Missing project_id in service-account JSON")
            }

            val accessToken = exchangeJwtForAccessToken(clientEmail, privateKeyPem,
                "https://www.googleapis.com/auth/compute.readonly")

            val out = mutableListOf<CloudInstanceState>()
            var pageToken: String? = null
            do {
                val url = buildString {
                    append("https://compute.googleapis.com/compute/v1/projects/$projectId/aggregated/instances?maxResults=500")
                    if (pageToken != null) append("&pageToken=").append(URLEncoder.encode(pageToken, "UTF-8"))
                }
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                val rawBody = http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = try {
                            JSONObject(raw).optJSONObject("error")?.optString("message") ?: resp.message
                        } catch (_: Exception) { resp.message }
                        throw IllegalStateException("GCP API HTTP ${resp.code}: $msg")
                    }
                    raw
                }
                val root = JSONObject(rawBody)
                val items = root.optJSONObject("items")
                if (items != null) {
                    val zoneKeys = items.keys()
                    while (zoneKeys.hasNext()) {
                        val zoneKey = zoneKeys.next()
                        val zoneObj = items.optJSONObject(zoneKey) ?: continue
                        val instances = zoneObj.optJSONArray("instances") ?: continue
                        val zone = zoneKey.removePrefix("zones/")
                        for (i in 0 until instances.length()) {
                            val inst = instances.optJSONObject(i) ?: continue
                            val rawStatus = inst.optString("status", "unknown")
                            val normStatus = when (rawStatus) {
                                "RUNNING" -> "running"
                                "TERMINATED", "STOPPED" -> "stopped"
                                "STOPPING" -> "stopping"
                                "STAGING", "PROVISIONING" -> "starting"
                                else -> "unknown"
                            }
                            val publicIp = pickPublicIp(inst)
                            val privateIp = pickPrivateIp(inst)
                            out += CloudInstanceState(
                                id = inst.optString("name", "gce-${inst.optString("id")}"),
                                name = inst.optString("name", "gce-instance"),
                                ip = publicIp,
                                privateIp = privateIp,
                                status = normStatus,
                                rawStatus = rawStatus,
                                region = zone,
                                metadata = mapOf("zone" to zone, "project" to projectId)
                            )
                        }
                    }
                }
                pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
            } while (pageToken != null)
            cachedInstances = out
            out
        }

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean =
        gcpPowerAction(bearerToken, instanceId, "start")

    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        gcpPowerAction(bearerToken, instanceId, "stop")

    /**
     * GCP /reset = hard power cycle (pressing reset button). There is no separate
     * graceful reboot endpoint in this API path; this is the closest equivalent.
     */
    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        gcpPowerAction(bearerToken, instanceId, "reset")

    /** GCP has no separate force restart — same reset endpoint as restartInstance. */
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        gcpPowerAction(bearerToken, instanceId, "reset")

    private suspend fun gcpPowerAction(
        bearerToken: String,
        instanceId: String,
        action: String
    ): Boolean = withContext(Dispatchers.IO) {
        val sa = try { JSONObject(bearerToken) } catch (_: Exception) { return@withContext false }
        val clientEmail = sa.optString("client_email").ifBlank { return@withContext false }
        val privateKeyPem = sa.optString("private_key").ifBlank { return@withContext false }
        val projectId = sa.optString("project_id").ifBlank { return@withContext false }

        // Find the zone from the cached instance list
        val zone = cachedInstances.firstOrNull { it.id == instanceId }?.metadata?.get("zone")
            ?: return@withContext false

        val accessToken = exchangeJwtForAccessToken(clientEmail, privateKeyPem,
            "https://www.googleapis.com/auth/compute")

        val url = "https://compute.googleapis.com/compute/v1/projects/$projectId/zones/$zone/instances/$instanceId/$action"
        val body = "{}".toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp -> resp.isSuccessful }
    }

    private fun exchangeJwtForAccessToken(
        clientEmail: String,
        privateKeyPem: String,
        scope: String
    ): String {
        val now = System.currentTimeMillis() / 1000
        val header = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
        }
        val claims = JSONObject().apply {
            put("iss", clientEmail)
            put("scope", scope)
            put("aud", "https://oauth2.googleapis.com/token")
            put("iat", now)
            put("exp", now + 3600)
        }
        val unsigned = "${b64UrlNoPad(header.toString())}.${b64UrlNoPad(claims.toString())}"
        val signature = signRs256(unsigned, privateKeyPem)
        val jwt = "$unsigned.$signature"

        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", jwt)
            .build()
        val req = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(body)
            .build()

        val raw = http.newCall(req).execute().use { resp ->
            val r = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("GCP OAuth2 exchange HTTP ${resp.code}: $r")
            }
            r
        }
        val token = JSONObject(raw).optString("access_token")
        if (token.isBlank()) throw IllegalStateException("GCP token exchange returned no access_token: $raw")
        return token
    }

    private fun signRs256(data: String, privateKeyPem: String): String {
        val pkcs8Der = pemToDer(privateKeyPem)
        val keySpec = PKCS8EncodedKeySpec(pkcs8Der)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(keySpec)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(data.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign())
    }

    /**
     * Strip PEM envelope headers and base64-decode the key bytes.
     * GCP service-account JSON ships PKCS#8 PEM (BEGIN PRIVATE KEY format),
     * not RSA-style (BEGIN RSA PRIVATE KEY).
     */
    private fun pemToDer(pem: String): ByteArray {
        val pemHeader = Regex("-----BEGIN[^-]*-----")
        val pemFooter = Regex("-----END[^-]*-----")
        val cleaned = pem
            .replace(pemHeader, "")
            .replace(pemFooter, "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(cleaned)
    }

    private fun b64UrlNoPad(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun parseInstances(json: String, projectId: String, accountName: String): List<ImportCandidate> {
        val out = mutableListOf<ImportCandidate>()
        val root = JSONObject(json)
        val items = root.optJSONObject("items") ?: return out
        // items is { "zones/us-central1-a": { "instances": [...] }, ... }
        val zoneKeys = items.keys()
        while (zoneKeys.hasNext()) {
            val zoneKey = zoneKeys.next()
            val zoneObj = items.optJSONObject(zoneKey) ?: continue
            val instances = zoneObj.optJSONArray("instances") ?: continue
            val zone = zoneKey.removePrefix("zones/")
            for (i in 0 until instances.length()) {
                val inst = instances.optJSONObject(i) ?: continue
                if (inst.optString("status") != "RUNNING") continue
                val name = inst.optString("name", "gce-instance")
                val natIp = pickPublicIp(inst)
                if (natIp.isNullOrBlank()) {
                    Logger.d("GcpComputeClient", "$name has no public IP — skipping")
                    continue
                }
                out += ImportCandidate(
                    profile = ConnectionProfile(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        host = natIp,
                        port = 22,
                        username = "",  // GCP per-instance metadata; user fills
                        authType = "publickey",
                        advancedSettings = """{"cloud_source":"gcp:$accountName","cloud_region":"$zone","cloud_id":"${inst.optString("id")}"}""",
                        createdAt = System.currentTimeMillis()
                    ),
                    sourceLabel = "GCP Compute / $zone"
                )
            }
        }
        return out
    }

    private fun pickPublicIp(instance: JSONObject): String? {
        val nics = instance.optJSONArray("networkInterfaces") ?: return null
        for (i in 0 until nics.length()) {
            val nic = nics.optJSONObject(i) ?: continue
            val accessConfigs = nic.optJSONArray("accessConfigs") ?: continue
            for (j in 0 until accessConfigs.length()) {
                val ac = accessConfigs.optJSONObject(j) ?: continue
                val nat = ac.optString("natIP")
                if (nat.isNotBlank()) return nat
            }
        }
        return null
    }

    private fun pickPrivateIp(instance: JSONObject): String? {
        val nics = instance.optJSONArray("networkInterfaces") ?: return null
        val nic = nics.optJSONObject(0) ?: return null
        return nic.optString("networkIP").ifBlank { null }
    }
}
