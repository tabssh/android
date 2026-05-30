package io.github.tabssh.cloud

import io.github.tabssh.hypervisor.oci.OciApiClient
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
 *
 * Only RUNNING instances with a reachable IP (public preferred, private
 * fallback) are returned. Instances with no IP at all are silently skipped —
 * there is no SSH address to import.
 */
class OciCloudClient : CloudProvider {

    override val type: CloudProviderType = CloudProviderType.OCI

    override suspend fun fetchInventory(bearerToken: String, accountName: String): List<ImportCandidate> {
        val creds       = JSONObject(bearerToken)
        val tenancy     = creds.getString("tenancy")
        val user        = creds.getString("user")
        val fingerprint = creds.getString("fingerprint")
        val region      = creds.getString("region")
        val compartment = creds.optString("compartment").takeIf { it.isNotBlank() } ?: tenancy
        val pem         = creds.getString("pem")
        val passphrase  = creds.optString("passphrase").takeIf { it.isNotBlank() }

        val keyMaterial = OciKeyMaterial.fromPem(pem, passphrase?.toCharArray())
        val apiClient = OciApiClient(
            tenancyOcid = tenancy,
            userOcid    = user,
            fingerprint = fingerprint,
            region      = region,
            keyMaterial = keyMaterial
        )

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
}
