package io.github.tabssh.cloud

import io.github.tabssh.storage.database.entities.ConnectionProfile

/**
 * Wave 5.1 — Cloud provider abstraction.
 *
 * Each provider (DigitalOcean, Hetzner, Linode, …) implements this single
 * `fetchInventory` method against its public REST API. The provider returns
 * a list of import candidates which the UI shows for confirmation; we don't
 * silently mutate the connections table behind the user's back.
 *
 * IPv4 / IPv6 handling: prefer the public IPv4 address when available; fall
 * back to v6 if the host has no v4 (a more common scenario as v4 exhaustion
 * grows). Hosts with no public address are skipped.
 */
interface CloudProvider {

    val type: CloudProviderType

    /**
     * Pull the live inventory using [bearerToken]. Throws on auth/network
     * failure — caller catches and reports to the user.
     */
    suspend fun fetchInventory(bearerToken: String, accountName: String): List<ImportCandidate>
}

/**
 * Tagged enum of supported providers. The string `tag` matches the
 * `cloud_accounts.provider` column.
 */
enum class CloudProviderType(val tag: String, val displayName: String, val tokenHelp: String) {
    DIGITALOCEAN("digitalocean", "DigitalOcean", "API token (Bearer)"),
    HETZNER("hetzner", "Hetzner Cloud", "API token (Bearer)"),
    LINODE("linode", "Linode (Akamai)", "Personal access token"),
    VULTR("vultr", "Vultr", "API key"),
    AWS("aws", "AWS EC2", "AKID:SECRET:REGION (e.g. AKIA…:wJal…:us-east-1)"),
    GCP("gcp", "GCP Compute Engine", "Paste full service-account JSON"),
    AZURE("azure", "Azure VMs", "TENANT:CLIENT_ID:CLIENT_SECRET:SUBSCRIPTION_ID");

    companion object {
        fun fromTag(tag: String): CloudProviderType? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * One pre-mapped import candidate: a [ConnectionProfile] proposal plus a
 * description / source tag. The UI shows these in a multi-select dialog
 * so the user can pick which to actually persist.
 */
data class ImportCandidate(
    val profile: ConnectionProfile,
    val sourceLabel: String,    // e.g. "DigitalOcean / nyc3"
    val skipped: String? = null // non-null = candidate skipped, with reason
)
