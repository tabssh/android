package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Hostinger VPS inventory and power controls using its public REST API. */
class HostingerClient : CloudProvider {

    override val type = CloudProviderType.HOSTINGER

    private val http = io.github.tabssh.network.SharedHttpClient.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchInventory(
        bearerToken: String,
        accountName: String
    ): List<ImportCandidate> = withContext(Dispatchers.IO) {
        HostingerApiParser.parseVirtualMachines(getVirtualMachines(bearerToken)).mapNotNull { vm ->
            val ip = vm.publicIpv4 ?: return@mapNotNull null
            ImportCandidate(
                profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = vm.name,
                    host = ip,
                    port = 22,
                    username = "root",
                    authType = "password",
                    advancedSettings = cloudAdvancedSettings(
                        "cloud_source" to "hostinger:$accountName",
                        "cloud_region" to vm.region.orEmpty(),
                        "cloud_id" to vm.id
                    ),
                    createdAt = System.currentTimeMillis()
                ),
                sourceLabel = "Hostinger / ${vm.region ?: "?"}"
            )
        }
    }

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> =
        withContext(Dispatchers.IO) {
            HostingerApiParser.parseVirtualMachines(getVirtualMachines(bearerToken)).map { vm ->
                CloudInstanceState(
                    id = vm.id,
                    name = vm.name,
                    ip = vm.publicIpv4,
                    privateIp = null,
                    status = CloudInstanceState.normalizeStatus(vm.rawStatus),
                    rawStatus = vm.rawStatus,
                    region = vm.region
                )
            }
        }

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(bearerToken, instanceId, "start")

    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(bearerToken, instanceId, "stop")

    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(bearerToken, instanceId, "restart")

    // Hostinger's restart action fully stops and starts the VPS, so it is also
    // the closest equivalent to the cloud UI's force-restart action.
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        postAction(bearerToken, instanceId, "restart")

    private fun getVirtualMachines(token: String): JSONArray {
        val request = Request.Builder()
            .url("$API_BASE_URL/api/vps/v1/virtual-machines")
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/json")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            val body = readCloudResponseBody(response.body)
            if (!response.isSuccessful) throwApiError(response.code, response.message)
            JSONArray(body)
        }
    }

    private suspend fun postAction(token: String, instanceId: String, action: String): Boolean =
        withContext(Dispatchers.IO) {
            val id = instanceId.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Hostinger virtual machine ID must be a positive integer")
            val request = Request.Builder()
                .url("$API_BASE_URL/api/vps/v1/virtual-machines/$id/$action")
                .header("Authorization", "Bearer ${token.trim()}")
                .header("Accept", "application/json")
                .post(ByteArray(0).toRequestBody())
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    throw CloudAuthException("Hostinger credentials rejected (HTTP ${response.code})")
                }
                response.isSuccessful
            }
        }

    private fun throwApiError(code: Int, message: String): Nothing {
        if (code == 401 || code == 403) {
            throw CloudAuthException("Hostinger credentials rejected (HTTP $code)")
        }
        throw IllegalStateException("Hostinger API HTTP $code: $message")
    }

    private companion object {
        const val API_BASE_URL = "https://developers.hostinger.com"
    }
}

internal data class HostingerVirtualMachine(
    val id: String,
    val name: String,
    val rawStatus: String,
    val region: String?,
    val publicIpv4: String?
)

internal object HostingerApiParser {

    fun parseVirtualMachines(items: JSONArray): List<HostingerVirtualMachine> = buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optLong("id", 0L)
            if (id <= 0L) continue
            add(
                HostingerVirtualMachine(
                    id = id.toString(),
                    name = item.optString("hostname").ifBlank { "hostinger-$id" },
                    rawStatus = item.optString("state", "unknown"),
                    region = item.optInt("data_center_id", item.optInt("dataCenterId", 0))
                        .takeIf { it > 0 }?.let { "DC $it" },
                    publicIpv4 = firstIpv4(item.optJSONArray("ipv4"))
                )
            )
        }
    }

    private fun firstIpv4(addresses: JSONArray?): String? {
        if (addresses == null) return null
        for (index in 0 until addresses.length()) {
            val address = addresses.optJSONObject(index)?.optString("address").orEmpty()
            if (isIpv4Address(address)) return address
        }
        return null
    }

    private fun isIpv4Address(value: String): Boolean {
        val octets = value.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.all(Char::isDigit) &&
                octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
