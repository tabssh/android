package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Wave 8.4 — Azure VM inventory.
 *
 * Auth: client-credentials OAuth2 (service principal / app registration).
 *  1. POST `client_id`, `client_secret`, `grant_type=client_credentials`,
 *     `scope=https://management.azure.com/.default` to
 *     https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token.
 *  2. Receive `access_token`.
 *  3. GET https://management.azure.com/subscriptions/{sub}/providers/
 *      Microsoft.Compute/virtualMachines?api-version=2023-03-01 with
 *     `Authorization: Bearer …`.
 *
 * Quirk: the VM list endpoint returns VM metadata but NOT the public IP
 * directly. To get public IP we'd need to follow `properties.networkProfile
 * .networkInterfaces[].id` → Microsoft.Network NIC → `ipConfigurations[]
 * .properties.publicIPAddress.id` → Microsoft.Network publicIPAddress →
 * `properties.ipAddress`. That's THREE more API calls per VM and turns one
 * fetch into N round-trips.
 *
 * For this first cut we use the simpler `expand=instanceView` and
 * `Microsoft.Resources resources?$filter=resourceType eq 'Microsoft.Network/publicIPAddresses'`
 * trick: fetch ALL public IPs in the subscription up front and join them
 * to VMs by NIC association. Two requests, no per-VM fan-out.
 *
 * Token format: `TENANT_ID:CLIENT_ID:CLIENT_SECRET:SUBSCRIPTION_ID`
 * (4 colon-separated values).
 *
 * Limitations:
 *  - Single subscription per cloud-account row.
 *  - Reserved IPs that are NOT bound to a NIC are ignored (can't attach
 *    them to a VM without the NIC link, which we don't fetch in v1).
 */
class AzureVmClient : CloudProvider {

    override val type = CloudProviderType.AZURE

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val parts = bearerToken.split(":", limit = 4)
        if (parts.size != 4) {
            throw IllegalStateException("Azure token must be 'TENANT:CLIENT_ID:CLIENT_SECRET:SUBSCRIPTION_ID'")
        }
        val (tenant, clientId, clientSecret, subscriptionId) = parts

        val accessToken = exchangeForAccessToken(tenant, clientId, clientSecret)

        // Fetch VMs (single page is fine for typical accounts; v1 punts on
        // pagination — `nextLink` is in the response if needed later).
        val vms = jsonGet(
            "https://management.azure.com/subscriptions/$subscriptionId/providers/Microsoft.Compute/virtualMachines?api-version=2023-03-01",
            accessToken
        ).optJSONArray("value") ?: org.json.JSONArray()

        // Fetch public IPs in one shot — Resource Graph would be cleanest, but
        // the simple list endpoint is enough for v1.
        val publicIps = fetchPublicIpsByNicId(subscriptionId, accessToken)

        val out = mutableListOf<ImportCandidate>()
        for (i in 0 until vms.length()) {
            val vm = vms.optJSONObject(i) ?: continue
            val name = vm.optString("name", "azure-vm")
            val location = vm.optString("location", "")
            val props = vm.optJSONObject("properties") ?: continue
            val nicArray = props.optJSONObject("networkProfile")?.optJSONArray("networkInterfaces") ?: continue

            // First NIC's public IP wins.
            var pubIp: String? = null
            for (j in 0 until nicArray.length()) {
                val nicId = nicArray.optJSONObject(j)?.optString("id") ?: continue
                pubIp = publicIps[nicId]
                if (!pubIp.isNullOrBlank()) break
            }
            if (pubIp.isNullOrBlank()) {
                Logger.d("AzureVmClient", "$name has no public IP — skipping")
                continue
            }
            out += ImportCandidate(
                profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    host = pubIp,
                    port = 22,
                    username = "azureuser", // Azure CLI default; user can edit
                    authType = "publickey",
                    advancedSettings = """{"cloud_source":"azure:$accountName","cloud_region":"$location"}""",
                    createdAt = System.currentTimeMillis()
                ),
                sourceLabel = "Azure / $location"
            )
        }
        Logger.i("AzureVmClient", "Fetched ${out.size} Azure VMs for sub=$subscriptionId account=$accountName")
        out
    }

    private fun exchangeForAccessToken(tenant: String, clientId: String, clientSecret: String): String {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "client_credentials")
            .add("scope", "https://management.azure.com/.default")
            .build()
        val req = Request.Builder()
            .url("https://login.microsoftonline.com/$tenant/oauth2/v2.0/token")
            .post(body)
            .build()
        val raw = http.newCall(req).execute().use { resp ->
            val r = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Azure OAuth2 HTTP ${resp.code}: $r")
            }
            r
        }
        val token = JSONObject(raw).optString("access_token")
        if (token.isBlank()) throw IllegalStateException("Azure token exchange returned no access_token")
        return token
    }

    /** NIC.id → publicIp string. Walks all NICs in the sub, follows their
     *  `publicIPAddress.id` reference, fetches each public IP once. Two
     *  list calls, then map. */
    private fun fetchPublicIpsByNicId(subscriptionId: String, token: String): Map<String, String> {
        val nicById = mutableMapOf<String, String>() // nicId -> publicIpId
        val nics = jsonGet(
            "https://management.azure.com/subscriptions/$subscriptionId/providers/Microsoft.Network/networkInterfaces?api-version=2023-09-01",
            token
        ).optJSONArray("value") ?: return emptyMap()
        for (i in 0 until nics.length()) {
            val nic = nics.optJSONObject(i) ?: continue
            val nicId = nic.optString("id")
            val ipConfigs = nic.optJSONObject("properties")?.optJSONArray("ipConfigurations") ?: continue
            for (j in 0 until ipConfigs.length()) {
                val pubRef = ipConfigs.optJSONObject(j)?.optJSONObject("properties")?.optJSONObject("publicIPAddress")?.optString("id")
                if (!pubRef.isNullOrBlank()) {
                    nicById[nicId] = pubRef
                    break
                }
            }
        }

        val ipById = mutableMapOf<String, String>() // publicIpId -> ipAddress
        val pips = jsonGet(
            "https://management.azure.com/subscriptions/$subscriptionId/providers/Microsoft.Network/publicIPAddresses?api-version=2023-09-01",
            token
        ).optJSONArray("value") ?: return emptyMap()
        for (i in 0 until pips.length()) {
            val pip = pips.optJSONObject(i) ?: continue
            val id = pip.optString("id")
            val addr = pip.optJSONObject("properties")?.optString("ipAddress") ?: ""
            if (id.isNotBlank() && addr.isNotBlank()) ipById[id] = addr
        }

        return nicById.mapValues { (_, pubId) -> ipById[pubId] ?: "" }
            .filter { it.value.isNotBlank() }
    }

    private fun jsonGet(url: String, token: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        val raw = http.newCall(req).execute().use { resp ->
            val r = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Azure HTTP ${resp.code}: ${tryAzureError(r) ?: resp.message}")
            }
            r
        }
        return JSONObject(raw)
    }

    private fun tryAzureError(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.let { "${it.optString("code")}: ${it.optString("message")}" }
    } catch (_: Exception) { null }
}
