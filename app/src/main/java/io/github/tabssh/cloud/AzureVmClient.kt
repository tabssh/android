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
 * Quirk: the VM list endpoint returns VM metadata but NOT any IP address.
 * `properties.networkProfile.networkInterfaces[]` holds only an ARM
 * resource id, so the addresses live behind the Network API.
 *
 * Rather than a per-VM fan-out (NIC → publicIPAddress, times the VM count),
 * we make two subscription-scoped list calls — every NIC, then every public
 * IP — and join them in memory by `nicId → publicIPAddress.id →
 * ipAddress`. Private addresses come from the same NIC walk for free.
 *
 * Token format: `TENANT_ID:CLIENT_ID:CLIENT_SECRET:SUBSCRIPTION_ID`
 * (4 colon-separated values).
 *
 * Limitations:
 *  - Single subscription per cloud-account row.
 *  - Reserved IPs not bound to a NIC are ignored — there is no VM to
 *    attach them to.
 */
class AzureVmClient : CloudProvider {

    override val type = CloudProviderType.AZURE

    private val http: OkHttpClient = io.github.tabssh.network.SharedHttpClient.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Cached instances from the last fetchLiveInstances call; used to resolve
     * resource group for power actions. Volatile because fetchLiveInstances
     * and the power-action methods can run on different IO threads when the
     * caller reuses one client instance across operations.
     */
    @Volatile
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
        val nicIps = fetchIpsByNicId(subscriptionId, accessToken)

        val out = mutableListOf<ImportCandidate>()
        for (vm in vms) {
            val name = vm.optString("name", "azure-vm")
            val location = vm.optString("location", "")
            val props = vm.optJSONObject("properties") ?: continue
            val nicArray = props.optJSONObject("networkProfile")?.optJSONArray("networkInterfaces") ?: continue

            // First NIC with a public IP wins.
            var pubIp: String? = null
            for (j in 0 until nicArray.length()) {
                val nicId = nicArray.optJSONObject(j)?.optString("id") ?: continue
                val ip = nicIps[nicId]?.publicIp
                if (!ip.isNullOrBlank()) {
                    pubIp = ip
                    break
                }
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
                    // Azure CLI default; user can edit
                    username = "azureuser",
                    authType = "publickey",
                    advancedSettings = cloudAdvancedSettings(
                        "cloud_source" to "azure:$accountName",
                        "cloud_region" to location
                    ),
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

            // `statusOnly=true` is the documented way to get runtime power state
            // on a subscription-wide list. `$expand=instanceView` is rejected
            // without a `$filter`, and `$filter` only applies to scale-set members.
            // Paginate via nextLink in case there are many VMs.
            val vms = jsonGetAll(
                "https://management.azure.com/subscriptions/$subscriptionId/providers/Microsoft.Compute/virtualMachines?statusOnly=true&api-version=2023-03-01",
                accessToken
            )

            val nicIps = fetchIpsByNicId(subscriptionId, accessToken)

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
                var privIp: String? = null
                if (nicArray != null) {
                    for (j in 0 until nicArray.length()) {
                        val nicId = nicArray.optJSONObject(j)?.optString("id") ?: continue
                        val ips = nicIps[nicId] ?: continue
                        if (pubIp.isNullOrBlank() && ips.publicIp.isNotBlank()) pubIp = ips.publicIp
                        if (privIp.isNullOrBlank() && ips.privateIp.isNotBlank()) privIp = ips.privateIp
                        if (!pubIp.isNullOrBlank() && !privIp.isNullOrBlank()) break
                    }
                }

                // Key by "resourceGroup/name" — a bare VM name is not unique across
                // resource groups, so a name-only id could act on the wrong VM.
                out += CloudInstanceState(
                    id = "$resourceGroup/$name",
                    name = name,
                    ip = pubIp?.ifBlank { null },
                    privateIp = privIp,
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

    /**
     * `powerOff` with the default `skipShutdown=false` is the graceful stop and
     * keeps the VM's compute allocation. `deallocate` would also release those
     * resources and change the billing state, so it is not a stop.
     */
    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        azureVmAction(bearerToken, instanceId, "powerOff")

    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        azureVmAction(bearerToken, instanceId, "restart")

    /**
     * Azure has no hard power cycle for a VM — the only action paths are
     * start, powerOff, restart and deallocate, and `restart` takes no force
     * flag. This is therefore the same graceful restart as above; there is
     * nothing to escalate to.
     */
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        azureVmAction(bearerToken, instanceId, "restart")

    private suspend fun azureVmAction(
        bearerToken: String,
        instanceId: String,
        action: String
    ): Boolean = withContext(Dispatchers.IO) {
        val parts = bearerToken.split(":", limit = 4)
        if (parts.size != 4) return@withContext false
        val (tenant, clientId, clientSecret, subscriptionId) = parts

        // instanceId is "resourceGroup/name" (see fetchLiveInstances); the URL
        // needs the bare VM name, the cache lookup the composite id.
        val rg = cachedInstances.firstOrNull { it.id == instanceId }?.metadata?.get("resourceGroup")
            ?: instanceId.substringBefore('/', "").takeIf { it.isNotEmpty() }
            ?: return@withContext false
        val vmName = instanceId.substringAfterLast('/')

        // exchangeForAccessToken throws CloudAuthException on 401/403 — let it
        // propagate so the UI can distinguish a bad credential from a
        // transient failure. Any other exception (network, parse) falls back
        // to a generic failed-action result.
        val accessToken = try {
            exchangeForAccessToken(tenant, clientId, clientSecret)
        } catch (e: CloudAuthException) {
            throw e
        } catch (_: Exception) { return@withContext false }

        val url = "https://management.azure.com/subscriptions/$subscriptionId/resourceGroups/$rg/providers/Microsoft.Compute/virtualMachines/$vmName/$action?api-version=2023-03-01"
        val body = "{}".toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw CloudAuthException("Azure power action rejected (HTTP ${resp.code})")
            }
            resp.isSuccessful
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
                if (resp.code == 401 || resp.code == 403) {
                    throw CloudAuthException("Azure OAuth2 rejected client credentials (HTTP ${resp.code})")
                }
                throw IllegalStateException("Azure OAuth2 HTTP ${resp.code}: $r")
            }
            r
        }
        val token = JSONObject(raw).optString("access_token")
        if (token.isBlank()) throw IllegalStateException("Azure token exchange returned no access_token")
        return token
    }

    /**
     * NIC.id → addresses for that NIC. Walks every NIC in the subscription,
     * follows each `publicIPAddress.id` reference, and fetches every public IP
     * once. Two paginated list calls, then an in-memory join — no per-VM
     * fan-out. Private addresses come from the same NIC walk and cost nothing
     * extra.
     */
    private fun fetchIpsByNicId(subscriptionId: String, token: String): Map<String, NicIps> {
        // nicId -> publicIpId
        val nicById = mutableMapOf<String, String>()
        val privateByNicId = mutableMapOf<String, String>()
        val nics = jsonGetAll(
            "https://management.azure.com/subscriptions/$subscriptionId/providers/Microsoft.Network/networkInterfaces?api-version=2023-09-01",
            token
        )
        for (nic in nics) {
            val nicId = nic.optString("id")
            val ipConfigs = nic.optJSONObject("properties")?.optJSONArray("ipConfigurations") ?: continue
            for (j in 0 until ipConfigs.length()) {
                val cfgProps = ipConfigs.optJSONObject(j)?.optJSONObject("properties") ?: continue
                val pubRef = cfgProps.optJSONObject("publicIPAddress")?.optString("id")
                if (!pubRef.isNullOrBlank() && !nicById.containsKey(nicId)) {
                    nicById[nicId] = pubRef
                }
                val priv = cfgProps.optString("privateIPAddress")
                if (priv.isNotBlank() && !privateByNicId.containsKey(nicId)) {
                    privateByNicId[nicId] = priv
                }
            }
        }

        // publicIpId -> ipAddress
        val ipById = mutableMapOf<String, String>()
        val pips = jsonGetAll(
            "https://management.azure.com/subscriptions/$subscriptionId/providers/Microsoft.Network/publicIPAddresses?api-version=2023-09-01",
            token
        )
        for (pip in pips) {
            val id = pip.optString("id")
            val addr = pip.optJSONObject("properties")?.optString("ipAddress") ?: ""
            if (id.isNotBlank() && addr.isNotBlank()) ipById[id] = addr
        }

        val nicIds = (nicById.keys + privateByNicId.keys).distinct()
        return nicIds.associateWith { nicId ->
            NicIps(
                publicIp = nicById[nicId]?.let { ipById[it] }.orEmpty(),
                privateIp = privateByNicId[nicId].orEmpty()
            )
        }
    }

    /** The addresses found on one NIC. Either field may be empty. */
    private data class NicIps(val publicIp: String, val privateIp: String)

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
                if (resp.code == 401 || resp.code == 403) {
                    throw CloudAuthException("Azure token rejected (HTTP ${resp.code}): ${tryAzureError(r) ?: resp.message}")
                }
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
