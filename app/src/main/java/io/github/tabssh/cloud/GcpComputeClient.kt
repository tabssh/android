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
import android.util.Base64
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

    private val http: OkHttpClient = io.github.tabssh.network.SharedHttpClient.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Cached instances from the last fetchLiveInstances call; used to resolve
     * zone for power actions. Volatile because fetchLiveInstances and the
     * power-action methods can run on different IO threads when the caller
     * reuses one client instance across operations.
     */
    @Volatile
    private var cachedInstances: List<CloudInstanceState> = emptyList()

    private companion object {
        /** Bounds the stop→start wait in [restartInstance] so a stuck instance cannot hang it. */
        const val RESTART_POLL_ATTEMPTS = 30
        const val RESTART_POLL_INTERVAL_MS = 2_000L
    }

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val sa = try {
            JSONObject(bearerToken)
        } catch (_: Exception) {
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
                append("https://compute.googleapis.com/compute/v1/projects/$projectId/aggregated/instances?maxResults=500&returnPartialSuccess=true")
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
                    if (resp.code == 401 || resp.code == 403) {
                        throw CloudAuthException("GCP token rejected (HTTP ${resp.code}): $msg")
                    }
                    throw IllegalStateException("GCP API HTTP ${resp.code}: $msg")
                }
                raw
            }
            out += parseInstances(body, accountName)
            pageToken = JSONObject(body).optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)

        Logger.i("GcpComputeClient", "Fetched ${out.size} GCE instances for project=$projectId account=$accountName")
        out
    }

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> =
        withContext(Dispatchers.IO) {
            val sa = try { JSONObject(bearerToken) } catch (_: Exception) {
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
                    append("https://compute.googleapis.com/compute/v1/projects/$projectId/aggregated/instances?maxResults=500&returnPartialSuccess=true")
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
                        if (resp.code == 401 || resp.code == 403) {
                            throw CloudAuthException("GCP token rejected (HTTP ${resp.code}): $msg")
                        }
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
                                "SUSPENDED" -> "suspended"
                                "STOPPING", "SUSPENDING", "DEPROVISIONING" -> "stopping"
                                "PENDING", "PENDING_STOP" -> "stopping"
                                "STAGING", "PROVISIONING" -> "starting"
                                "REPAIRING" -> "rebooting"
                                else -> "unknown"
                            }
                            val publicIp = pickPublicIp(inst)
                            val privateIp = pickPrivateIp(inst)
                            val name = inst.optString("name", "gce-${inst.optString("id")}")
                            out += CloudInstanceState(
                                // Key by "zone/name" — an instance name is only unique per
                                // zone, so a name-only id could act on the wrong instance.
                                id = "$zone/$name",
                                name = inst.optString("name", "gce-instance"),
                                ip = publicIp,
                                privateIp = privateIp,
                                status = normStatus,
                                rawStatus = rawStatus,
                                region = zoneToRegion(zone),
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

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean {
        // A SUSPENDED instance is not brought back by `start` — it needs `resume`.
        val raw = cachedInstances.firstOrNull { it.id == instanceId }?.rawStatus
        return gcpPowerAction(bearerToken, instanceId, if (raw == "SUSPENDED") "resume" else "start")
    }

    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        gcpPowerAction(bearerToken, instanceId, "stop")

    /**
     * GCP has no `restart` method: `reset` is a hard power cycle. The graceful
     * path is `stop` (documented as a clean shutdown) followed by `start` once
     * the instance has actually reached STOPPED.
     */
    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean {
        if (!gcpPowerAction(bearerToken, instanceId, "stop")) return false
        if (!awaitStopped(bearerToken, instanceId)) return false
        return gcpPowerAction(bearerToken, instanceId, "start")
    }

    /** GCP `reset` is a hard power cycle — the force-restart equivalent. */
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        gcpPowerAction(bearerToken, instanceId, "reset")

    /**
     * Poll the instance until it leaves the transitional states, so a restart
     * issues `start` only after the guest has finished shutting down. Bounded
     * so a stuck instance cannot block the caller forever.
     */
    private suspend fun awaitStopped(bearerToken: String, instanceId: String): Boolean =
        withContext(Dispatchers.IO) {
            val sa = try {
                JSONObject(bearerToken)
            } catch (_: Exception) {
                return@withContext false
            }
            val projectId = sa.optString("project_id").ifBlank { return@withContext false }
            val privateKeyPem = sa.optString("private_key").ifBlank { return@withContext false }
            val clientEmail = sa.optString("client_email").ifBlank { return@withContext false }
            val zone = resolveZone(instanceId) ?: return@withContext false
            val instanceName = instanceId.substringAfterLast('/')
            val accessToken = exchangeJwtForAccessToken(
                clientEmail, privateKeyPem, "https://www.googleapis.com/auth/compute"
            )
            val url = "https://compute.googleapis.com/compute/v1/projects/$projectId/zones/$zone/instances/$instanceName"
            repeat(RESTART_POLL_ATTEMPTS) {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                val status = http.newCall(req).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        throw CloudAuthException("GCP token rejected (HTTP ${resp.code})")
                    }
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("GCP API HTTP ${resp.code}: ${resp.message}")
                    }
                    JSONObject(resp.body?.string().orEmpty()).optString("status")
                }
                if (status == "STOPPED" || status == "TERMINATED") return@withContext true
                if (status == "RUNNING") return@withContext false
                Thread.sleep(RESTART_POLL_INTERVAL_MS)
            }
            false
        }

    private fun resolveZone(instanceId: String): String? =
        cachedInstances.firstOrNull { it.id == instanceId }?.metadata?.get("zone")
            ?: instanceId.substringBefore('/', "").takeIf { it.isNotEmpty() }

    /**
     * Zone names end in a `-<letter><digit>` shard suffix (`us-central1-a`),
     * so the region is that suffix stripped. The UI labels `region` as a
     * region, and a zone is misleading there.
     */
    private fun zoneToRegion(zone: String): String =
        zone.replace(Regex("-[a-z]$"), "")

    private suspend fun gcpPowerAction(
        bearerToken: String,
        instanceId: String,
        action: String
    ): Boolean = withContext(Dispatchers.IO) {
        val sa = try {
            JSONObject(bearerToken)
        } catch (_: Exception) {
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

        // instanceId is "zone/name" (see fetchLiveInstances); the URL needs both
        // parts separately. Prefer the cache, fall back to splitting the id.
        val zone = resolveZone(instanceId)
            ?: throw IllegalStateException("GCP instance id must be \"zone/name\": $instanceId")
        val instanceName = instanceId.substringAfterLast('/')

        val accessToken = exchangeJwtForAccessToken(clientEmail, privateKeyPem,
            "https://www.googleapis.com/auth/compute")

        val url = "https://compute.googleapis.com/compute/v1/projects/$projectId/zones/$zone/instances/$instanceName/$action"
        val body = "{}".toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw CloudAuthException("GCP power action rejected (HTTP ${resp.code})")
            }
            resp.isSuccessful
        }
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
                if (resp.code == 401 || resp.code == 403) {
                    throw CloudAuthException("GCP OAuth2 rejected service-account credentials (HTTP ${resp.code})")
                }
                // Log only the OAuth error field, never the raw body: a token-endpoint
                // response could carry credential material.
                Logger.d("GcpComputeClient", "GCP OAuth2 exchange HTTP ${resp.code}: ${oauthError(r)}")
                throw IllegalStateException("GCP OAuth2 exchange HTTP ${resp.code}: ${resp.message}")
            }
            r
        }
        val token = JSONObject(raw).optString("access_token")
        if (token.isBlank()) {
            Logger.d("GcpComputeClient", "GCP token exchange returned no access_token: ${oauthError(raw)}")
            throw IllegalStateException("GCP token exchange returned no access_token")
        }
        return token
    }

    /** The OAuth `error` code from a token-endpoint body, or `unknown` if unparseable. */
    private fun oauthError(body: String): String =
        runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty().ifBlank { "unknown" }

    private fun signRs256(data: String, privateKeyPem: String): String {
        val pkcs8Der = pemToDer(privateKeyPem)
        val keySpec = PKCS8EncodedKeySpec(pkcs8Der)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(keySpec)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            sig.sign(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
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
        return Base64.decode(cleaned, Base64.DEFAULT)
    }

    private fun b64UrlNoPad(s: String): String =
        Base64.encodeToString(
            s.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

    private fun parseInstances(json: String, accountName: String): List<ImportCandidate> {
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
            val region = zoneToRegion(zone)
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
                        // GCP per-instance metadata; user fills
                        username = "",
                        authType = "publickey",
                        advancedSettings = cloudAdvancedSettings(
                            "cloud_source" to "gcp:$accountName",
                            "cloud_region" to region,
                            "cloud_zone" to zone,
                            // Key cloud_id as "zone/name" to match fetchLiveInstances.
                            "cloud_id" to "$zone/$name"
                        ),
                        createdAt = System.currentTimeMillis()
                    ),
                    sourceLabel = "GCP Compute / $region"
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
