package io.github.tabssh.hypervisor.oci

import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
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

    internal companion object {
        /**
         * Ceiling on a single response body we buffer. An instance page is
         * tens of kilobytes; the endpoint is still a trust boundary, and
         * `body.string()` would read whatever it is sent until the heap is
         * gone. Matches the bound used by the other hypervisor clients.
         */
        private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        /**
         * Hard stop on `opc-next-page` following. A server that keeps handing
         * back a next-page token — whether by bug or by malice — would
         * otherwise spin this loop forever while the accumulated list grows.
         * 100 items per page makes this a 20 000-instance ceiling.
         */
        private const val MAX_PAGES = 200

        /**
         * Return [value] unchanged if it is a well-formed OCID, otherwise
         * throw. OCIDs are interpolated straight into request paths, so a
         * value carrying `/` or `?` would retarget the request at another
         * resource or endpoint.
         */
        internal fun requireValidOcid(value: String): String {
            require(value.startsWith("ocid1.") && value.length <= 255 &&
                value.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) {
                "Invalid OCID"
            }
            return value
        }
    }

    private val identityHost = "identity.$region.oci.oraclecloud.com"
    private val iaasHost = "iaas.$region.oraclecloud.com"
    private val identityBaseUrl = "https://$identityHost/20160918"
    private val iaasBaseUrl = "https://$iaasHost/20160918"

    // OCI uses two distinct hostnames (identity.* and iaas.*) that carry
    // separate TLS leaf certs.  We need one captured-pin holder per host so
    // TOFU prompts for each cert are shown once and both pins are persisted.
    //
    // Storage format: "sha_identity;sha_iaas" — FIXED POSITIONS.
    // An absent entry is represented by an empty string, NOT omitted.
    // e.g. only-IAAS pin is stored as ";sha_iaas", only-identity as "sha_id;".
    //
    // DO NOT filter blank entries when parsing — that collapses positions and
    // assigns the IAAS sha to the identity slot, triggering TOFU on every
    // subsequent action call.
    private val pinnedParts: List<String> = pinnedCertSha256
        ?.split(";")
        ?.map { it.trim() }
        ?: emptyList()
    private val identityPinnedSha: String? = pinnedParts.getOrNull(0)?.takeIf { it.isNotBlank() }
    private val iaasPinnedSha: String?     = pinnedParts.getOrNull(1)?.takeIf { it.isNotBlank() }

    private val identityCapturedPin = io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.CapturedPin()
    private val iaasCapturedPin    = io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.CapturedPin()

    /**
     * Returns the fixed-position semicolon-delimited pin string to persist.
     * Format is always "sha_identity;sha_iaas" — either slot may be blank but
     * the semicolon separator is always present so positions never shift.
     * Returns null only when both slots are blank (nothing to persist).
     */
    fun getCapturedCertSha256(): String? {
        val idSha   = identityCapturedPin.sha256?.takeIf { it.isNotBlank() } ?: identityPinnedSha ?: ""
        val iaasSha = iaasCapturedPin.sha256?.takeIf    { it.isNotBlank() } ?: iaasPinnedSha     ?: ""
        if (idSha.isBlank() && iaasSha.isBlank()) return null
        return "$idSha;$iaasSha"
    }

    private val signer = OciSigner(tenancyOcid, userOcid, fingerprint, keyMaterial)

    // Separate HTTP clients so each endpoint's TLS session has its own pin.
    // Bounded timeouts so a stalled OCI endpoint cannot hang the UI forever.
    private val identityClient: OkHttpClient = io.github.tabssh.network.SharedHttpClient.client.newBuilder()
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

    private val iaasClient: OkHttpClient = io.github.tabssh.network.SharedHttpClient.client.newBuilder()
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
     * Read at most [MAX_RESPONSE_BYTES] from [response], rejecting anything
     * larger instead of buffering it. `body.string()` is unbounded.
     */
    private fun readBounded(response: okhttp3.Response): String {
        val source = response.body?.source() ?: return ""
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) {
            Logger.w("OciAPI", "Response exceeded $MAX_RESPONSE_BYTES bytes — rejecting")
            throw java.io.IOException("OCI response too large")
        }
        return source.readByteString().utf8()
    }

    /**
     * Describe an error body by size only. The text is server-controlled and
     * can carry resource identifiers, so the log records how much came back
     * rather than what it said.
     */
    private fun errorBodySummary(response: okhttp3.Response): String {
        val length = try {
            readBounded(response).length
        } catch (e: java.io.IOException) {
            Logger.d("OciAPI", "error body unreadable: ${e.message}")
            return "<unreadable body>"
        }
        return if (length == 0) "<no body>" else "<$length-char body>"
    }

    /** Parse [raw] as a JSON array, reporting a malformed reply as an [java.io.IOException]. */
    private fun jsonArrayOf(raw: String, what: String): JSONArray =
        try {
            JSONArray(raw)
        } catch (e: org.json.JSONException) {
            throw java.io.IOException("OCI $what: malformed JSON array response", e)
        }

    /** Parse [raw] as a JSON object, reporting a malformed reply as an [java.io.IOException]. */
    private fun jsonObjectOf(raw: String, what: String): JSONObject =
        try {
            JSONObject(raw)
        } catch (e: org.json.JSONException) {
            throw java.io.IOException("OCI $what: malformed JSON object response", e)
        }

    /**
     * Live credential check — pulls the IAM user record. Returns true on
     * 200, false on 401/403/404/etc. Throws on transport errors, and on a
     * malformed stored user OCID, so the onboarding flow can distinguish
     * "key wrong" (false) from "no network" / "bad config" (exception).
     */
    suspend fun validateCredentials(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$identityBaseUrl/users/${requireValidOcid(userOcid)}".toHttpUrl())
                .get()
                .build()
            identityClient.newCall(request).execute().use { resp ->
                val ok = resp.isSuccessful
                if (!ok) {
                    Logger.w("OciAPI", "validateCredentials HTTP ${resp.code}: ${errorBodySummary(resp)}")
                }
                ok
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("OciAPI", "validateCredentials transport error", e)
            throw e
        }
    }

    /**
     * List all Compute instances in a compartment, following `opc-next-page`
     * pagination until the header is absent. Each page requests up to 100
     * items (OCI's documented maximum per page for this endpoint). Pages are
     * accumulated in memory; most tenancies have < 1 000 instances per
     * compartment so memory pressure is negligible. Following stops early on
     * a repeated page token or after [MAX_PAGES] pages, so a server that
     * never drops the header cannot spin this loop forever.
     *
     * Returns an empty list (not an exception) on HTTP errors so the caller
     * can show an empty state rather than crash the fragment.
     */
    suspend fun listInstances(compartmentOcid: String): List<OciInstance> =
        withContext(Dispatchers.IO) {
            val accumulated = mutableListOf<OciInstance>()
            var pageToken: String? = null
            val seenTokens = mutableSetOf<String>()
            var pages = 0

            do {
                pages++
                val urlBuilder = "$iaasBaseUrl/instances".toHttpUrl().newBuilder()
                    .addQueryParameter("compartmentId", compartmentOcid)
                    .addQueryParameter("limit", "100")
                if (pageToken != null) {
                    urlBuilder.addQueryParameter("page", pageToken)
                }
                val request = Request.Builder().url(urlBuilder.build()).get().build()
                val nextPage: String? = iaasClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Logger.e("OciAPI", "listInstances HTTP ${resp.code}: ${errorBodySummary(resp)}")
                        return@withContext accumulated
                    }
                    accumulated.addAll(parseInstances(jsonArrayOf(readBounded(resp), "listInstances")))
                    resp.header("opc-next-page")
                }
                // A token we have already followed means the server is cycling
                // us through the same pages; stop rather than loop forever.
                if (nextPage != null && !seenTokens.add(nextPage)) {
                    Logger.w("OciAPI", "listInstances: repeated opc-next-page token — stopping after $pages pages")
                    return@withContext accumulated
                }
                if (pages >= MAX_PAGES && nextPage != null) {
                    Logger.w("OciAPI", "listInstances: page limit $MAX_PAGES reached — returning partial list")
                    return@withContext accumulated
                }
                pageToken = nextPage
            } while (pageToken != null)

            accumulated
        }

    suspend fun getInstance(instanceOcid: String): OciInstance? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$iaasBaseUrl/instances/${requireValidOcid(instanceOcid)}".toHttpUrl())
                .get().build()
            iaasClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w("OciAPI", "getInstance HTTP ${resp.code}")
                    return@withContext null
                }
                parseInstance(jsonObjectOf(readBounded(resp), "getInstance"))
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
        val url = "$iaasBaseUrl/instances/${requireValidOcid(instanceOcid)}".toHttpUrl().newBuilder()
            .addQueryParameter("action", action.wireValue)
            .build()
        // OCI's InstanceAction API takes NO request body — sending the literal
        // "{}" (a 2-byte JSON payload) was previously rejected by OCI's
        // request validator with HTTP 400, since this endpoint declares no
        // request-body schema at all. A genuinely zero-length body still
        // gets a correct Content-Length/x-content-sha256 signed (OciSigner
        // triggers its body-digest triplet purely off the POST/PUT/PATCH
        // method, computed over whatever bytes are actually present — an
        // empty ByteArray hashes/sizes correctly), matching what OCI expects
        // for a body-less POST action.
        val body = "".toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(body).build()
        iaasClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.e("OciAPI", "instanceAction(${action.wireValue}) HTTP ${resp.code}: " +
                    errorBodySummary(resp))
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
                    jsonArrayOf(readBounded(r), "vnicAttachments")
                }

            // 2. Walk attachments, fetch each VNIC, return the first primary's IPs
            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                val vnicId = att.optString("vnicId").takeIf { it.isNotEmpty() } ?: continue
                val vnicUrl = "$iaasBaseUrl/vnics/${requireValidOcid(vnicId)}".toHttpUrl()
                val vnic = iaasClient.newCall(Request.Builder().url(vnicUrl).get().build())
                    .execute().use { r ->
                        if (!r.isSuccessful) null else jsonObjectOf(readBounded(r), "vnic")
                    } ?: continue
                if (!vnic.optBoolean("isPrimary", false)) continue
                val pub = vnic.optString("publicIp").takeIf { it.isNotEmpty() }
                val priv = vnic.optString("privateIp").takeIf { it.isNotEmpty() }
                return@withContext pub to priv
            }
            null to null
        } catch (e: CancellationException) {
            throw e
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
