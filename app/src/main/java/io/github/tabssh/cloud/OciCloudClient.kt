package io.github.tabssh.cloud

import io.github.tabssh.hypervisor.oci.OciApiClient
import io.github.tabssh.hypervisor.oci.OciInstanceAction
import io.github.tabssh.hypervisor.oci.OciKeyMaterial
import io.github.tabssh.storage.database.entities.ConnectionProfile
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
 *   tls_pin     — semicolon-delimited identity;iaas TLS leaf-cert SHA-256
 *                 pair, written back by CloudAccountManagerActivity after the
 *                 first TOFU accept.  Missing/empty → TOFU on first connect.
 *
 * Only RUNNING instances with a reachable IP (public preferred, private
 * fallback) are returned. Instances with no IP at all are silently skipped —
 * there is no SSH address to import.
 *
 * Pin-persistence contract
 * ========================
 * OCI endpoints use TLS certs that are NOT in the Android trust store (they
 * are Oracle-managed but may be self-signed or behind a private CA).
 * OciApiClient runs TOFU the first time it sees an unknown cert.  To avoid
 * a TOFU prompt on every app open the caller must:
 *
 *   1. Read the current `tls_pin` from the stored token JSON (may be absent).
 *   2. Call [fetchLiveInstances] / any action.
 *   3. If [getCapturedCertSha256] returns a non-null value that differs from
 *      the value already in the JSON, the caller MUST write the updated JSON
 *      back to the Keystore so the next connect skips TOFU.
 *
 * [CloudAccountManagerActivity] implements step 3 via `persistOciCloudPin()`.
 */
class OciCloudClient : CloudProvider {

    override val type: CloudProviderType = CloudProviderType.OCI

    /**
     * Holds the most recently created API client so the caller can read any
     * newly-captured TLS pin after each operation via [getCapturedCertSha256].
     */
    private var lastApiClient: OciApiClient? = null

    /**
     * Returns the full semicolon-delimited pin set after the most recent
     * API operation, or null if no client has been used yet.  Delegates to
     * [OciApiClient.getCapturedCertSha256] which merges existing + newly
     * captured SHAs so incremental persist calls are idempotent.
     */
    fun getCapturedCertSha256(): String? = lastApiClient?.getCapturedCertSha256()

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
            pinnedCertSha256 = pinnedSha
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
            val normStatus = when (rawStatus) {
                "RUNNING"                       -> "running"
                "STOPPED", "TERMINATED"         -> "stopped"
                "STARTING", "PROVISIONING"      -> "starting"
                "STOPPING", "TERMINATING"       -> "stopping"
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

    /** OCI SOFTSTOP sends an ACPI shutdown signal for a graceful guest OS stop. */
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
        } catch (_: Exception) {
            false
        }
    }
}
