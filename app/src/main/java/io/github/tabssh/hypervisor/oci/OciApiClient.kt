package io.github.tabssh.hypervisor.oci

import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OCI Compute (and Identity, for credential validation) API client.
 *
 * Endpoint base depends on the service:
 *   - Identity: `https://identity.<region>.oci.oraclecloud.com`
 *   - Compute / Networking (VNICs): `https://iaas.<region>.oraclecloud.com`
 *
 * All requests carry an HTTP Signature (`OciSigner`) — see
 * `signingrequests.htm`. We reuse the project's
 * `HypervisorTrustManagerFactory` so OCI inherits the same TLS pinning
 * behaviour as Proxmox/XCP-ng/VMware (`verifySsl=true` by default for OCI
 * since their endpoints have valid public certs).
 *
 * Mirrors the shape of `ProxmoxApiClient`: simple suspend functions per
 * call, no callback hell.
 */
class OciApiClient(
    private val tenancyOcid: String,
    private val userOcid: String,
    private val fingerprint: String,
    private val region: String,
    private val keyMaterial: OciKeyMaterial,
    private val verifySsl: Boolean = true,
    private val pinnedCertSha256: String? = null
) {

    private val identityHost = "identity.$region.oci.oraclecloud.com"
    private val iaasHost = "iaas.$region.oraclecloud.com"
    private val identityBaseUrl = "https://$identityHost/20160918"
    private val iaasBaseUrl = "https://$iaasHost/20160918"

    // OCI uses two distinct hostnames (identity.* and iaas.*) that carry
    // separate TLS leaf certs.  We need one captured-pin holder per host so
    // TOFU prompts for each cert are shown once and both pins are persisted.
    // The stored pinnedCertSha256 column may hold "sha_id;sha_iaas" (semicolon
    // delimited).  Legacy single-sha rows are treated as the identity pin only.
    private val pinnedParts: List<String> = pinnedCertSha256
        ?.split(";")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    private val identityPinnedSha: String? = pinnedParts.getOrNull(0)
    private val iaasPinnedSha: String? = pinnedParts.getOrNull(1)

    private val identityCapturedPin = io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.CapturedPin()
    private val iaasCapturedPin    = io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.CapturedPin()

    /**
     * Returns the full semicolon-delimited pin string to persist after connect.
     * Combines any newly-captured SHAs with the already-stored SHAs so that
     * incremental persistence (identity captured first, iaas captured later)
     * never loses a previously-stored pin.
     */
    fun getCapturedCertSha256(): String? {
        val idSha   = identityCapturedPin.sha256?.takeIf { it.isNotBlank() } ?: identityPinnedSha
        val iaasSha = iaasCapturedPin.sha256?.takeIf    { it.isNotBlank() } ?: iaasPinnedSha
        return listOfNotNull(idSha, iaasSha)
            .filter { it.isNotBlank() }
            .joinToString(";")
            .takeIf { it.isNotBlank() }
    }

    private val signer = OciSigner(tenancyOcid, userOcid, fingerprint, keyMaterial)

    // Separate HTTP clients so each endpoint's TLS session has its own pin.
    // Bounded timeouts so a stalled OCI endpoint cannot hang the UI forever.
    private val identityClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .also { b ->
            io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.installTrust(
                b, verifySsl, identityPinnedSha, identityCapturedPin, identityHost, 443
            )
        }
        .addInterceptor(signer.asInterceptor())
        .build()

    private val iaasClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .also { b ->
            io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.installTrust(
                b, verifySsl, iaasPinnedSha, iaasCapturedPin, iaasHost, 443
            )
        }
        .addInterceptor(signer.asInterceptor())
        .build()

    /** Cancel any in-flight calls on both clients. Safe from Activity.onDestroy(). */
    fun cancelAll() {
        try { identityClient.dispatcher.cancelAll() } catch (_: Exception) {}
        try { iaasClient.dispatcher.cancelAll() } catch (_: Exception) {}
    }

    /**
     * Live credential check — pulls the IAM user record. Returns true on
     * 200, false on 401/403/404/etc. Throws only on transport errors so
     * the onboarding flow can distinguish "key wrong" (false) from "no
     * network" (exception).
     */
    suspend fun validateCredentials(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$identityBaseUrl/users/$userOcid".toHttpUrl())
                .get()
                .build()
            identityClient.newCall(request).execute().use { resp ->
                val ok = resp.isSuccessful
                if (!ok) {
                    Logger.w("OciAPI", "validateCredentials HTTP ${resp.code}: " +
                        (resp.body?.string()?.take(200) ?: "<no body>"))
                }
                ok
            }
        } catch (e: Exception) {
            Logger.e("OciAPI", "validateCredentials transport error", e)
            throw e
        }
    }

    /**
     * List Compute instances in a compartment. Returns the first page
     * only (no pagination — matches the spec's "out of scope" call). If
     * the response carries an `opc-next-page` header, more rows exist
     * and are silently dropped.
     */
    suspend fun listInstances(compartmentOcid: String): List<OciInstance> =
        withContext(Dispatchers.IO) {
            val url = "$iaasBaseUrl/instances".toHttpUrl().newBuilder()
                .addQueryParameter("compartmentId", compartmentOcid)
                .addQueryParameter("limit", "100")
                .build()
            val request = Request.Builder().url(url).get().build()
            iaasClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.e("OciAPI", "listInstances HTTP ${resp.code}: " +
                        (resp.body?.string()?.take(300) ?: "<no body>"))
                    return@withContext emptyList<OciInstance>()
                }
                val raw = resp.body?.string().orEmpty()
                parseInstances(JSONArray(raw))
            }
        }

    suspend fun getInstance(instanceOcid: String): OciInstance? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$iaasBaseUrl/instances/$instanceOcid".toHttpUrl())
                .get().build()
            iaasClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w("OciAPI", "getInstance HTTP ${resp.code}")
                    return@withContext null
                }
                val raw = resp.body?.string().orEmpty()
                parseInstance(JSONObject(raw))
            }
        }

    /**
     * POST /instances/{id}?action={action} — start/stop/softstop/reset/
     * softreset. OCI returns the updated Instance resource on 200. We
     * just return whether the request succeeded.
     */
    suspend fun instanceAction(
        instanceOcid: String,
        action: OciInstanceAction
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "$iaasBaseUrl/instances/$instanceOcid".toHttpUrl().newBuilder()
            .addQueryParameter("action", action.wireValue)
            .build()
        // Empty JSON body — OCI requires Content-Length even for actionless POSTs.
        val body = "{}".toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(body).build()
        iaasClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.e("OciAPI", "instanceAction(${action.wireValue}) HTTP ${resp.code}: " +
                    (resp.body?.string()?.take(200) ?: "<no body>"))
                false
            } else {
                Logger.i("OciAPI", "instanceAction(${action.wireValue}) succeeded for $instanceOcid")
                true
            }
        }
    }

    /**
     * Resolve an instance's primary public + private IP by walking VNIC
     * attachments → VNICs. Returns `(publicIp, privateIp)`; either may be
     * null. The "primary" VNIC is the only one we look at.
     */
    suspend fun getInstancePublicIp(
        instanceOcid: String,
        compartmentOcid: String
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            // 1. List VNIC attachments for this instance
            val vaUrl = "$iaasBaseUrl/vnicAttachments".toHttpUrl().newBuilder()
                .addQueryParameter("compartmentId", compartmentOcid)
                .addQueryParameter("instanceId", instanceOcid)
                .build()
            val attachments = iaasClient.newCall(Request.Builder().url(vaUrl).get().build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@withContext null to null
                    JSONArray(r.body?.string().orEmpty())
                }

            // 2. Walk attachments, fetch each VNIC, return the first primary's IPs
            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                val vnicId = att.optString("vnicId").takeIf { it.isNotEmpty() } ?: continue
                val vnicUrl = "$iaasBaseUrl/vnics/$vnicId".toHttpUrl()
                val vnic = iaasClient.newCall(Request.Builder().url(vnicUrl).get().build())
                    .execute().use { r ->
                        if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
                    } ?: continue
                if (!vnic.optBoolean("isPrimary", false)) continue
                val pub = vnic.optString("publicIp").takeIf { it.isNotEmpty() }
                val priv = vnic.optString("privateIp").takeIf { it.isNotEmpty() }
                return@withContext pub to priv
            }
            null to null
        } catch (e: Exception) {
            Logger.w("OciAPI", "getInstancePublicIp failed for $instanceOcid", e)
            null to null
        }
    }

    private fun parseInstances(arr: JSONArray): List<OciInstance> {
        val out = mutableListOf<OciInstance>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out += parseInstance(obj)
        }
        return out
    }

    private fun parseInstance(obj: JSONObject): OciInstance = OciInstance(
        id = obj.optString("id"),
        displayName = obj.optString("displayName", obj.optString("id")),
        lifecycleState = obj.optString("lifecycleState", "UNKNOWN"),
        region = obj.optString("region").takeIf { it.isNotEmpty() },
        availabilityDomain = obj.optString("availabilityDomain"),
        compartmentId = obj.optString("compartmentId"),
        shape = obj.optString("shape"),
        timeCreated = obj.optString("timeCreated")
    )
}
