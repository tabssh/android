package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.storage.database.entities.VpsHost

/**
 * Works out which tracked VPS host an SSH connection belongs to, and which saved connections a tracked domain
 * points at. Links are computed from names and addresses every time, so they follow an IP or domain change with
 * nothing stored beyond an optional override. A connection's [ConnectionProfile.vpsLink] and a domain's
 * [Domain.linkedHost] mean the same thing: null is automatic, "" is deliberately none, anything else is the id of
 * the row to use (falling back to automatic if that row no longer exists). Many domains may name one connection.
 */
object HostLinkResolver {

    /** The tracked VPS host [connection] belongs to. */
    fun vpsFor(connection: ConnectionProfile, vpsHosts: List<VpsHost>): VpsHost? = when (val link = connection.vpsLink) {
        null -> autoVps(connection.host, vpsHosts)
        "" -> null
        else -> vpsHosts.firstOrNull { it.id == link } ?: autoVps(connection.host, vpsHosts)
    }

    /** Every connection that belongs to [vps], in the order given. */
    fun connectionsForVps(vps: VpsHost, connections: List<ConnectionProfile>, vpsHosts: List<VpsHost>): List<ConnectionProfile> =
        connections.filter { vpsFor(it, vpsHosts)?.id == vps.id }

    /**
     * The saved connections [domain] points at: the one it is explicitly linked to, or none when linked to none, or
     * automatically every connection whose host is this domain or a subdomain of it (the longest tracked domain
     * wins) and every connection whose VPS entry lists this domain, or a subdomain of it, as linked.
     */
    fun connectionsForDomain(
        domain: Domain,
        connections: List<ConnectionProfile>,
        vpsHosts: List<VpsHost>,
        domains: List<Domain>
    ): List<ConnectionProfile> {
        when (val link = domain.linkedHost) {
            "" -> return emptyList()
            null -> Unit
            else -> connections.firstOrNull { it.id == link }?.let { return listOf(it) }
        }
        return connections.filter { c ->
            longestDomainFor(c.host, domains)?.id == domain.id ||
                vpsFor(c, vpsHosts)?.let { v -> linkedDomainNames(v).any { longestDomainFor(it, domains)?.id == domain.id } } == true
        }
    }

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
            linkedDomainNames(v).any { it == h } ||
                (v.hostname.contains('.') && v.hostname.trim().lowercase().trimEnd('.') == h)
        }
    }

    /** The domain names a VPS entry's linked-domain field lists (several may be separated by commas, semicolons or spaces). */
    internal fun linkedDomainNames(vps: VpsHost): List<String> =
        vps.linkedDomain.orEmpty().split(Regex("[,;\\s]+")).map { it.trim().lowercase().trimEnd('.') }.filter { it.isNotEmpty() }

    /** The tracked domain [name] equals or sits under; the longest such domain wins. */
    internal fun longestDomainFor(name: String, domains: List<Domain>): Domain? {
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
