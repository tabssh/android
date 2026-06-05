package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Cached instances from the last fetchLiveInstances call; used to resolve resource group for power actions. */
    private var cachedInstances: List<CloudInstanceState> = emptyList()

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

        // Paginate across all VM pages via nextLink until exhausted.
        val vms = jsonGetAll(
            "https://management.azure.com/subscriptions/$subscriptionId/providers/Microsoft.Compute/virtualMachines?api-version=2023-03-01",
            accessToken
        )

        // Fetch public IPs in one shot — Resource Graph would be cleanest, but
        // the simple list endpoint is enough for v1.
        val publicIps = fetchPublicIpsByNicId(subscriptionId, accessToken)

        val out = mutableListOf<ImportCandidate>()
        for (vm in vms) {
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

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> =
        withContext(Dispatchers.IO) {
            val parts = bearerToken.split(":", limit = 4)
            if (parts.size != 4) throw IllegalStateException("Azure token must be 'TENANT:CLIENT_ID:CLIENT_SECRET:SUBSCRIPTION_ID'")
            val (tenant, clientId, clientSecret, subscriptionId) = parts

            val accessToken = exchangeForAccessToken(tenant, clientId, clientSecret)

            // Request instanceView expansion so power state is included in one call.
            // Paginate via nextLink in case there are many VMs.
            val vms = jsonGetAll(
                "https://management.azure.com/subscriptions/$subscriptionId/providers/Microsoft.Compute/virtualMachines?\$expand=instanceView&api-version=2023-03-01",
                accessToken
            )

            val publicIps = fetchPublicIpsByNicId(subscriptionId, accessToken)

            val out = mutableListOf<CloudInstanceState>()
            for (vm in vms) {
                val name = vm.optString("name", "azure-vm")
                val location = vm.optString("location", "")

                // Extract resource group from the VM resource id (/subscriptions/.../resourceGroups/RG/providers/...)
                val vmId = vm.optString("id", "")
                val resourceGroup = vmId.split("/").let { parts2 ->
                    val idx = parts2.indexOfFirst { it.equals("resourceGroups", ignoreCase = true) }
                    if (idx >= 0 && idx + 1 < parts2.size) parts2[idx + 1] else ""
                }

                // Parse power state from instanceView statuses
                val props = vm.optJSONObject("properties")
                val statuses = props?.optJSONObject("instanceView")?.optJSONArray("statuses")
                var rawStatus = "unknown"
                if (statuses != null) {
                    for (j in 0 until statuses.length()) {
                        val code = statuses.optJSONObject(j)?.optString("code", "").orEmpty()
                        if (code.startsWith("PowerState/")) {
                            rawStatus = code.removePrefix("PowerState/")
                            break
                        }
                    }
                }
                val normStatus = when (rawStatus) {
                    "running" -> "running"
                    "deallocated", "stopped" -> "stopped"
                    "starting" -> "starting"
                    "stopping", "deallocating" -> "stopping"
                    else -> "unknown"
                }

                val nicArray = props?.optJSONObject("networkProfile")?.optJSONArray("networkInterfaces")
                var pubIp: String? = null
                if (nicArray != null) {
                    for (j in 0 until nicArray.length()) {
                        val nicId = nicArray.optJSONObject(j)?.optString("id") ?: continue
                        pubIp = publicIps[nicId]
                        if (!pubIp.isNullOrBlank()) break
                    }
                }

                out += CloudInstanceState(
                    id = name,
                    name = name,
                    ip = pubIp?.ifBlank { null },
                    privateIp = null,
                    status = normStatus,
                    rawStatus = rawStatus,
                    region = location.ifBlank { null },
                    metadata = mapOf("resourceGroup" to resourceGroup, "subscription" to subscriptionId)
                )
            }
            cachedInstances = out
            out
        }

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean =
        azureVmAction(bearerToken, instanceId, "start")

    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        azureVmAction(bearerToken, instanceId, "deallocate")

    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        azureVmAction(bearerToken, instanceId, "restart")

    /** Azure restart with skipShutdown=true skips the guest OS shutdown sequence. */
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        azureVmAction(bearerToken, instanceId, "restart?skipShutdown=true")

    private suspend fun azureVmAction(
        bearerToken: String,
        instanceId: String,
        action: String
    ): Boolean = withContext(Dispatchers.IO) {
        val parts = bearerToken.split(":", limit = 4)
        if (parts.size != 4) return@withContext false
        val (tenant, clientId, clientSecret, subscriptionId) = parts

        val rg = cachedInstances.firstOrNull { it.id == instanceId }?.metadata?.get("resourceGroup")
            ?: return@withContext false

        val accessToken = try {
            exchangeForAccessToken(tenant, clientId, clientSecret)
        } catch (_: Exception) { return@withContext false }

        val url = "https://management.azure.com/subscriptions/$subscriptionId/resourceGroups/$rg/providers/Microsoft.Compute/virtualMachines/$instanceId/$action?api-version=2023-03-01"
        val body = "{}".toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            resp.code == 200 || resp.code == 202 || resp.isSuccessful
        }
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
     *  paginated list calls, then map. */
    private fun fetchPublicIpsByNicId(subscriptionId: String, token: String): Map<String, String> {
        val nicById = mutableMapOf<String, String>() // nicId -> publicIpId
        val nics = jsonGetAll(
            "https://management.azure.com/subscriptions/$subscriptionId/providers/Microsoft.Network/networkInterfaces?api-version=2023-09-01",
            token
        )
        for (nic in nics) {
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
        val pips = jsonGetAll(
            "https://management.azure.com/subscriptions/$subscriptionId/providers/Microsoft.Network/publicIPAddresses?api-version=2023-09-01",
            token
        )
        for (pip in pips) {
            val id = pip.optString("id")
            val addr = pip.optJSONObject("properties")?.optString("ipAddress") ?: ""
            if (id.isNotBlank() && addr.isNotBlank()) ipById[id] = addr
        }

        return nicById.mapValues { (_, pubId) -> ipById[pubId] ?: "" }
            .filter { it.value.isNotBlank() }
    }

    /**
     * Paginate an Azure list endpoint that returns `{ "value": [...], "nextLink": "..." }`.
     * Follows nextLink until exhausted and returns all items across all pages.
     */
    private fun jsonGetAll(url: String, token: String): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        var nextUrl: String? = url
        while (nextUrl != null) {
            val page = jsonGet(nextUrl, token)
            val arr = page.optJSONArray("value")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { items += it }
                }
            }
            nextUrl = page.optString("nextLink").takeIf { it.isNotBlank() }
        }
        return items
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
