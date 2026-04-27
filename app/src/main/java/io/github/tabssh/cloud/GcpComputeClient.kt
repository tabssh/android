package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Wave 8.2 — GCP Compute Engine inventory.
 *
 * Auth flow (service-account JSON → access token):
 *  1. User pastes the entire service-account JSON as the cloud-account
 *     "token". We extract `client_email`, `private_key` (PEM), `project_id`.
 *  2. Build a JWT with `iss=client_email`, `scope=…compute.readonly`,
 *     `aud=https://oauth2.googleapis.com/token`, `iat`, `exp` (1h max).
 *  3. Sign header.payload with RS256 using the private key.
 *  4. POST `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=<JWT>`
 *     to https://oauth2.googleapis.com/token; receive `access_token`.
 *  5. GET `https://compute.googleapis.com/compute/v1/projects/{project}/aggregated/instances`
 *     with `Authorization: Bearer <access_token>`. The aggregated endpoint
 *     returns instances across ALL zones in one call — saves us iterating.
 *
 * Limitations:
 *  - We don't cache tokens — every refresh = new JWT exchange. Tokens are
 *    valid 1h so caching is a worthwhile follow-up but isn't blocking.
 *  - We pull the first 500 instances per zone (page size cap). For larger
 *    projects, follow-up patch needed.
 *  - We pick `networkInterfaces[0].accessConfigs[0].natIP` (the public
 *    address). Private-only instances are skipped.
 *
 * Privacy: the service-account JSON contains a private key. Stored
 * AES-GCM-encrypted under Android Keystore via SecurePasswordManager.
 */
class GcpComputeClient : CloudProvider {

    override val type = CloudProviderType.GCP

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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

        val accessToken = exchangeJwtForAccessToken(clientEmail, privateKeyPem)

        val req = Request.Builder()
            .url("https://compute.googleapis.com/compute/v1/projects/$projectId/aggregated/instances?maxResults=500")
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

        parseInstances(body, projectId, accountName).also {
            Logger.i("GcpComputeClient", "Fetched ${it.size} GCE instances for project=$projectId account=$accountName")
        }
    }

    private fun exchangeJwtForAccessToken(clientEmail: String, privateKeyPem: String): String {
        val now = System.currentTimeMillis() / 1000
        val header = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
        }
        val claims = JSONObject().apply {
            put("iss", clientEmail)
            put("scope", "https://www.googleapis.com/auth/compute.readonly")
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

    /** Strip `-----BEGIN PRIVATE KEY-----` envelope + base64-decode. GCP service-
     *  account JSON ships PKCS#8 PEM ("BEGIN PRIVATE KEY"), not RSA-style. */
    private fun pemToDer(pem: String): ByteArray {
        val cleaned = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
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
}
