package io.github.tabssh.cloud

import io.github.tabssh.hypervisor.oci.OciApiClient
import io.github.tabssh.hypervisor.oci.OciInstanceAction
import io.github.tabssh.hypervisor.oci.OciKeyMaterial
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * CloudProvider implementation for Oracle Cloud Infrastructure (OCI).
 *
 * The "bearerToken" parameter is a JSON blob containing all fields required
 * for OCI HTTP-signature auth. The [CloudAccountsFragment] OCI setup dialog
 * builds this JSON from individual form fields before storing it in the
 * encrypted Keystore entry.
 *
 * JSON fields:
 *   tenancy     — tenancy OCID  (ocid1.tenancy.oc1.…)
 *   user        — user OCID     (ocid1.user.oc1.…)
 *   fingerprint — public-key fingerprint shown in the OCI console (aa:bb:…)
 *   region      — region identifier (e.g. us-ashburn-1)
 *   compartment — compartment OCID; defaults to tenancy when blank
 *   pem         — RSA private key PEM (newlines embedded as literal \n in JSON)
 *   passphrase  — PEM passphrase; empty string when the key is unencrypted
 *   tls_pin     — legacy field ignored by current clients.
 *
 * Only RUNNING instances with a reachable IP (public preferred, private
 * fallback) are returned. Instances with no IP at all are silently skipped —
 * there is no SSH address to import.
 *
 * OCI API requests use platform CA and hostname validation. The legacy TLS pin
 * helpers remain temporarily for stored credential compatibility and return no
 * newly captured pins.
 */
class OciCloudClient : CloudProvider {

    companion object {
        private const val TAG = "OciCloudClient"
    }

    override val type: CloudProviderType = CloudProviderType.OCI

    /** Legacy compatibility accessor; current API clients do not capture TLS pins. */
    private var lastApiClient: OciApiClient? = null

    /** Always null: TLS leaf certificates are not persisted as host identity. */
    fun getCapturedCertSha256(): String? = lastApiClient?.getCapturedCertSha256()

    /** Legacy compatibility callback; current TLS clients never capture pins. */
    var onPinCaptured: (() -> Unit)? = null

    private fun buildApiClient(creds: JSONObject): OciApiClient {
        val tenancy     = creds.getString("tenancy")
        val user        = creds.getString("user")
        val fingerprint = creds.getString("fingerprint")
        val region      = creds.getString("region")
        val pem         = creds.getString("pem")
        val passphrase  = creds.optString("passphrase").takeIf { it.isNotBlank() }
        val pinnedSha   = creds.optString("tls_pin").takeIf { it.isNotBlank() }
        val keyMaterial = OciKeyMaterial.fromPem(pem, passphrase?.toCharArray())
        return OciApiClient(
            tenancyOcid      = tenancy,
            userOcid         = user,
            fingerprint      = fingerprint,
            region           = region,
            keyMaterial      = keyMaterial,
            pinnedCertSha256 = pinnedSha,
            onPinCaptured    = { onPinCaptured?.invoke() }
        ).also { lastApiClient = it }
    }

    override suspend fun fetchInventory(bearerToken: String, accountName: String): List<ImportCandidate> {
        val creds       = JSONObject(bearerToken)
        val compartment = creds.optString("compartment").takeIf { it.isNotBlank() }
            ?: creds.getString("tenancy")
        val apiClient   = buildApiClient(creds)

        val instances = apiClient.listInstances(compartment)
        val results   = mutableListOf<ImportCandidate>()

        for (inst in instances) {
            if (inst.lifecycleState != "RUNNING") continue

            val (publicIp, privateIp) = apiClient.getInstancePublicIp(inst.id, compartment)
            val ip = publicIp ?: privateIp ?: continue

            val advancedSettings = JSONObject()
                .put("cloud_source", "oci:$accountName")
                .toString()

            val profile = ConnectionProfile(
                name             = inst.displayName,
                host             = ip,
                port             = 22,
                username         = "opc",
                advancedSettings = advancedSettings
            )
            results += ImportCandidate(
                profile     = profile,
                sourceLabel = "OCI / ${inst.availabilityDomain}"
            )
        }
        return results
    }

    override suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState> {
        val creds       = JSONObject(bearerToken)
        val compartment = creds.optString("compartment").takeIf { it.isNotBlank() }
            ?: creds.getString("tenancy")
        val apiClient   = buildApiClient(creds)

        val instances = apiClient.listInstances(compartment)
        val out = mutableListOf<CloudInstanceState>()
        for (inst in instances) {
            val rawStatus = inst.lifecycleState
            // Terminated instances are gone for good — listing them as stopped
            // invites doomed start attempts, so skip them entirely.
            if (rawStatus == "TERMINATED" || rawStatus == "TERMINATING") continue
            val normStatus = when (rawStatus) {
                "RUNNING"                       -> "running"
                "STOPPED"                       -> "stopped"
                "STARTING", "PROVISIONING"      -> "starting"
                "STOPPING"                      -> "stopping"
                else                            -> "unknown"
            }
            val (publicIp, privateIp) = apiClient.getInstancePublicIp(inst.id, compartment)
            out += CloudInstanceState(
                id         = inst.id,
                name       = inst.displayName,
                ip         = publicIp,
                privateIp  = privateIp,
                status     = normStatus,
                rawStatus  = rawStatus,
                region     = inst.region,
                metadata   = mapOf("compartment" to compartment)
            )
        }
        return out
    }

    override suspend fun startInstance(bearerToken: String, instanceId: String): Boolean =
        ociAction(bearerToken, instanceId, OciInstanceAction.START)

    /**
     * OCI SOFTSTOP sends a shutdown command to the guest OS and powers the
     * instance off after 15 minutes. `STOP` is the immediate power cut, which
     * the graceful-stop contract rules out here.
     */
    override suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean =
        ociAction(bearerToken, instanceId, OciInstanceAction.SOFTSTOP)

    /** OCI SOFTRESET sends a graceful reboot signal to the guest OS. */
    override suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean =
        ociAction(bearerToken, instanceId, OciInstanceAction.SOFTRESET)

    /** OCI RESET is a hard power cycle with no guest OS notification. */
    override suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean =
        ociAction(bearerToken, instanceId, OciInstanceAction.RESET)

    private suspend fun ociAction(
        bearerToken: String,
        instanceId: String,
        action: OciInstanceAction
    ): Boolean {
        return try {
            val creds     = JSONObject(bearerToken)
            val apiClient = buildApiClient(creds)
            apiClient.instanceAction(instanceId, action)
        } catch (e: CancellationException) {
            // Never report a cancelled coroutine as a failed power action —
            // callers must see the cancellation, not a bogus "false".
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "ociAction(${action.wireValue}, $instanceId) failed", e)
            false
        }
    }
}
