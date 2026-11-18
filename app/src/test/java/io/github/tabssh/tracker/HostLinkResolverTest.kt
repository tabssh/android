package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.storage.database.entities.VpsHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A connection links to the VPS it belongs to by address, and a domain points at saved connections, unless overridden. */
class HostLinkResolverTest {

    private fun conn(host: String, vps: String? = null, id: String = host) =
        ConnectionProfile(id = id, name = host, host = host, username = "root", createdAt = 1L, modifiedAt = 1L, vpsLink = vps)

    private fun vps(id: String, v4: String? = null, v6: String? = null, linked: String? = null, host: String = "h$id") =
        VpsHost(id = id, tenant = "t", hostname = host, ipv4 = v4, ipv6 = v6, linkedDomain = linked, createdAt = 1L, modifiedAt = 1L)

    private fun domain(id: String, name: String, host: String? = null) = Domain(
        id = id, domainName = name, privacyProtection = "ON", statusAtRegistrar = "Active", autoRenew = "ON",
        createdAt = 1L, modifiedAt = 1L, linkedHost = host
    )

    private val servers = listOf(
        vps("v1", v4 = "198.51.100.10", v6 = "2001:db8::10", linked = "dns.example.com"),
        vps("v2", v4 = "203.0.113.5")
    )
    private val domains = listOf(domain("d1", "example.com"), domain("d2", "sub.example.com"), domain("d3", "other.org"))

    private fun connectionIds(d: Domain, all: List<ConnectionProfile>, tracked: List<Domain> = domains) =
        HostLinkResolver.connectionsForDomain(d, all, servers, tracked).map { it.id }

    @Test
    fun `a connection to an ipv4 links to that vps`() {
        assertEquals("v1", HostLinkResolver.vpsFor(conn("198.51.100.10"), servers)?.id)
    }

    @Test
    fun `a connection to an ipv6 in any written form links to that vps`() {
        assertEquals("v1", HostLinkResolver.vpsFor(conn("2001:0DB8:0:0:0:0:0:10"), servers)?.id)
    }

    @Test
    fun `a connection to the vps linked domain name links to that vps`() {
        assertEquals("v1", HostLinkResolver.vpsFor(conn("DNS.example.com."), servers)?.id)
    }

    @Test
    fun `a vps override of none stops automatic linking and a chosen id wins`() {
        assertNull(HostLinkResolver.vpsFor(conn("198.51.100.10", vps = ""), servers))
        assertEquals("v2", HostLinkResolver.vpsFor(conn("198.51.100.10", vps = "v2"), servers)?.id)
    }

    @Test
    fun `a vps id that no longer exists falls back to automatic`() {
        assertEquals("v1", HostLinkResolver.vpsFor(conn("198.51.100.10", vps = "gone"), servers)?.id)
    }

    @Test
    fun `a domain points at the connections whose host is it or sits under it`() {
        val all = listOf(conn("web.example.com"), conn("example.com"), conn("elsewhere.net"), conn("notexample.com"))
        assertEquals(listOf("web.example.com", "example.com"), connectionIds(domains[0], all))
    }

    @Test
    fun `the longest tracked domain gets a subdomain host`() {
        val all = listOf(conn("web.sub.example.com"))
        assertEquals(listOf("web.sub.example.com"), connectionIds(domains[1], all))
        assertEquals(emptyList(), connectionIds(domains[0], all))
    }

    @Test
    fun `a domain also points at connections to a vps that lists it as linked`() {
        val all = listOf(conn("198.51.100.10"), conn("203.0.113.5"))
        assertEquals(listOf("198.51.100.10"), connectionIds(domains[0], all))
    }

    @Test
    fun `many domains can name one connection`() {
        val web = conn("203.0.113.5", id = "web")
        val linked = listOf(domain("d1", "example.com", host = "web"), domain("d2", "other.org", host = "web"), domain("d3", "third.io"))
        assertEquals(listOf("web"), connectionIds(linked[0], listOf(web), linked))
        assertEquals(listOf("web"), connectionIds(linked[1], listOf(web), linked))
        assertEquals(emptyList(), connectionIds(linked[2], listOf(web), linked))
    }

    @Test
    fun `a domain linked to none points at nothing and a missing connection falls back to automatic`() {
        val all = listOf(conn("web.example.com"))
        assertEquals(emptyList(), connectionIds(domain("d1", "example.com", host = ""), all))
        assertEquals(listOf("web.example.com"), connectionIds(domain("d1", "example.com", host = "gone"), all))
    }

    @Test
    fun `the reverse lookup lists every connection that belongs to a vps`() {
        val all = listOf(conn("198.51.100.10"), conn("web.example.com"), conn("203.0.113.5", vps = "v1", id = "forced"))
        assertEquals(listOf("198.51.100.10", "forced"), HostLinkResolver.connectionsForVps(servers[0], all, servers).map { it.id })
    }
}
