package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.storage.database.entities.VpsHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A connection links to the VPS and domain it belongs to by address and name, unless the user overrides it. */
class HostLinkResolverTest {

    private fun conn(host: String, vps: String? = null, domain: String? = null, id: String = host) =
        ConnectionProfile(id = id, name = host, host = host, username = "root", createdAt = 1L, modifiedAt = 1L, vpsLink = vps, domainLink = domain)

    private fun vps(id: String, v4: String? = null, v6: String? = null, linked: String? = null, host: String = "h$id") =
        VpsHost(id = id, tenant = "t", hostname = host, ipv4 = v4, ipv6 = v6, linkedDomain = linked, createdAt = 1L, modifiedAt = 1L)

    private fun domain(id: String, name: String) = Domain(
        id = id, domainName = name, privacyProtection = "ON", statusAtRegistrar = "Active", autoRenew = "ON", createdAt = 1L, modifiedAt = 1L
    )

    private val servers = listOf(
        vps("v1", v4 = "198.51.100.10", v6 = "2001:db8::10", linked = "dns.example.com"),
        vps("v2", v4 = "203.0.113.5")
    )
    private val domains = listOf(domain("d1", "example.com"), domain("d2", "sub.example.com"), domain("d3", "other.org"))

    @Test
    fun `a connection to an ipv4 links to that vps`() {
        assertEquals("v1", HostLinkResolver.resolve(conn("198.51.100.10"), servers, domains).vps?.id)
    }

    @Test
    fun `a connection to an ipv6 in any written form links to that vps`() {
        assertEquals("v1", HostLinkResolver.resolve(conn("2001:0DB8:0:0:0:0:0:10"), servers, domains).vps?.id)
    }

    @Test
    fun `a connection to the vps linked domain name links to that vps`() {
        assertEquals("v1", HostLinkResolver.resolve(conn("DNS.example.com."), servers, domains).vps?.id)
    }

    @Test
    fun `a host links to the longest tracked domain it equals or sits under`() {
        assertEquals("d2", HostLinkResolver.resolve(conn("web.sub.example.com"), servers, domains).domain?.id)
        assertEquals("d1", HostLinkResolver.resolve(conn("web.example.com"), servers, domains).domain?.id)
        assertEquals("d1", HostLinkResolver.resolve(conn("example.com"), servers, domains).domain?.id)
        assertNull(HostLinkResolver.resolve(conn("notexample.com"), servers, domains).domain)
    }

    @Test
    fun `an address-only host takes the domain its vps is linked to`() {
        val links = HostLinkResolver.resolve(conn("198.51.100.10"), servers, domains)
        assertEquals("d1", links.domain?.id)
    }

    @Test
    fun `an explicit none stops automatic linking`() {
        val links = HostLinkResolver.resolve(conn("198.51.100.10", vps = "", domain = ""), servers, domains)
        assertNull(links.vps)
        assertNull(links.domain)
    }

    @Test
    fun `a chosen id wins over the automatic match`() {
        val links = HostLinkResolver.resolve(conn("198.51.100.10", vps = "v2", domain = "d3"), servers, domains)
        assertEquals("v2", links.vps?.id)
        assertEquals("d3", links.domain?.id)
    }

    @Test
    fun `a chosen id that no longer exists falls back to automatic`() {
        val links = HostLinkResolver.resolve(conn("198.51.100.10", vps = "gone", domain = "gone"), servers, domains)
        assertEquals("v1", links.vps?.id)
        assertEquals("d1", links.domain?.id)
    }

    @Test
    fun `the reverse lookup lists every connection that resolves to a row`() {
        val all = listOf(conn("198.51.100.10"), conn("web.example.com"), conn("elsewhere.net"), conn("203.0.113.5", vps = "v1", id = "forced"))
        assertEquals(listOf("198.51.100.10", "forced"), HostLinkResolver.connectionsForVps(servers[0], all, servers, domains).map { it.id })
        assertEquals(listOf("198.51.100.10", "web.example.com", "forced"), HostLinkResolver.connectionsForDomain(domains[0], all, servers, domains).map { it.id })
    }
}
