package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.storage.database.entities.VpsHost

/**
 * Works out which tracked VPS host and domain an SSH connection belongs to. Links are computed from the
 * connection's host every time, so they follow an IP or domain change with nothing stored. A connection can
 * override either side: [ConnectionProfile.vpsLink] / [ConnectionProfile.domainLink] null means automatic, ""
 * means deliberately none, and an id picks that row (falling back to automatic if the row no longer exists).
 */
object HostLinkResolver {

    data class Links(val vps: VpsHost?, val domain: Domain?)

    fun resolve(connection: ConnectionProfile, vpsHosts: List<VpsHost>, domains: List<Domain>): Links {
        val vps = when (val link = connection.vpsLink) {
            null -> autoVps(connection.host, vpsHosts)
            "" -> null
            else -> vpsHosts.firstOrNull { it.id == link } ?: autoVps(connection.host, vpsHosts)
        }
        val domain = when (val link = connection.domainLink) {
            null -> autoDomain(connection.host, vps, domains)
            "" -> null
            else -> domains.firstOrNull { it.id == link } ?: autoDomain(connection.host, vps, domains)
        }
        return Links(vps, domain)
    }

    /** Every connection that resolves to [vps], in the order given. */
    fun connectionsForVps(vps: VpsHost, connections: List<ConnectionProfile>, vpsHosts: List<VpsHost>, domains: List<Domain>): List<ConnectionProfile> =
        connections.filter { resolve(it, vpsHosts, domains).vps?.id == vps.id }

    /** Every connection that resolves to [domain], in the order given. */
    fun connectionsForDomain(domain: Domain, connections: List<ConnectionProfile>, vpsHosts: List<VpsHost>, domains: List<Domain>): List<ConnectionProfile> =
        connections.filter { resolve(it, vpsHosts, domains).domain?.id == domain.id }

    /** A host matches a VPS on its IPv4, then IPv6, then on the VPS's linked domain or its own fully qualified hostname. */
    internal fun autoVps(host: String, vpsHosts: List<VpsHost>): VpsHost? {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return null
        TrackerImportMerge.normalizeIpv4(h)?.let { v4 -> vpsHosts.firstOrNull { TrackerImportMerge.normalizeIpv4(it.ipv4) == v4 }?.let { return it } }
        if (':' in h) {
            val v6 = TrackerImportMerge.normalizeIpv6(h)
            vpsHosts.firstOrNull { TrackerImportMerge.normalizeIpv6(it.ipv6) == v6 }?.let { return it }
        }
        return vpsHosts.firstOrNull { v ->
            v.linkedDomain?.trim()?.lowercase()?.trimEnd('.') == h ||
                (v.hostname.contains('.') && v.hostname.trim().lowercase().trimEnd('.') == h)
        }
    }

    /**
     * A host matches the tracked domain it equals or is a subdomain of (the longest such domain wins). A host
     * that is only an address falls back to the domain its matched VPS is linked to.
     */
    internal fun autoDomain(host: String, vps: VpsHost?, domains: List<Domain>): Domain? =
        domainFor(host, domains) ?: vps?.linkedDomain?.let { domainFor(it, domains) }

    private fun domainFor(name: String, domains: List<Domain>): Domain? {
        val h = name.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return null
        return domains
            .filter { d ->
                val n = d.domainName.trim().lowercase().trimEnd('.')
                n.isNotEmpty() && (h == n || h.endsWith(".$n"))
            }
            .maxByOrNull { it.domainName.trim().length }
    }
}
