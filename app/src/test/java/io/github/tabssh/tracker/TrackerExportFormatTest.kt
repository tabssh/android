package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.storage.database.entities.VpsHost
import kotlin.test.Test
import kotlin.test.assertEquals

/** The domain CSV and VPS markdown exports stay valid, readable files and carry host links in a form that re-imports. */
class TrackerExportFormatTest {

    private fun domain(name: String, host: String? = null) = Domain(
        id = "id-$name", domainName = name, privacyProtection = "ON", statusAtRegistrar = "Active", autoRenew = "ON",
        expirationDate = 1_799_971_200_000L, createdAt = 1L, modifiedAt = 1L, linkedHost = host
    )

    private fun conn(id: String, name: String, host: String) =
        ConnectionProfile(id = id, name = name, host = host, username = "root", createdAt = 1L, modifiedAt = 1L)

    @Test
    fun `without links the domain csv is exactly the five column file`() {
        val csv = DomainCsvImportExport.export(listOf(domain("example.com"), domain("other.org")))
        val lines = csv.trimEnd().lines()
        assertEquals(DomainCsvImportExport.HEADER, lines[0])
        assertEquals(listOf(5, 5, 5), lines.map { it.split(',').size })
    }

    @Test
    fun `with links the csv gains a Linked host column that re-imports to the same links`() {
        val connections = listOf(conn("c1", "web", "198.51.100.10"), conn("c2", "db", "db.example.com"))
        val domains = listOf(
            domain("example.com", host = "c1"), domain("other.org", host = "c1"),
            domain("third.io", host = ""), domain("plain.net")
        )
        val csv = DomainCsvImportExport.export(domains, connections.associate { it.id to it.host })
        val lines = csv.trimEnd().lines()
        assertEquals(DomainCsvImportExport.HEADER + ",Linked host", lines[0])
        assertEquals(listOf(6, 6, 6, 6, 6), lines.map { it.split(',').size })

        val parsed = DomainCsvImportExport.parse(csv)
        val (relinked, warnings) = DomainCsvImportExport.applyHostRefs(parsed.domains, parsed.hostRefs, connections)
        assertEquals(emptyList(), warnings)
        assertEquals(
            mapOf("example.com" to "c1", "other.org" to "c1", "third.io" to "", "plain.net" to null),
            relinked.associate { it.domainName to it.linkedHost }
        )
        assertEquals(domains.map { it.expirationDate }, parsed.domains.map { it.expirationDate })
    }

    @Test
    fun `a linked host that matches no connection warns and stays automatic`() {
        val csv = DomainCsvImportExport.HEADER + ",Linked host\nexample.com,ON,Active,ON,Dec 16 2026,nowhere.example\n"
        val parsed = DomainCsvImportExport.parse(csv)
        val (relinked, warnings) = DomainCsvImportExport.applyHostRefs(parsed.domains, parsed.hostRefs, emptyList())
        assertEquals(listOf(null), relinked.map { it.linkedHost })
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a linked host can be named by connection name and a file with the column missing changes no link`() {
        val connections = listOf(conn("c1", "Web Box", "198.51.100.10"))
        val csv = DomainCsvImportExport.HEADER + ",Linked host\nexample.com,ON,Active,ON,Dec 16 2026,web box\n"
        val parsed = DomainCsvImportExport.parse(csv)
        assertEquals(listOf("c1"), DomainCsvImportExport.applyHostRefs(parsed.domains, parsed.hostRefs, connections).first.map { it.linkedHost })

        val existing = listOf(domain("example.com", host = "c1"))
        val plain = DomainCsvImportExport.parse(DomainCsvImportExport.HEADER + "\nexample.com,ON,Active,ON,Dec 16 2026\n")
        assertEquals("c1", TrackerImportMerge.mergeDomains(plain.domains, existing).rows.single().linkedHost)
    }

    @Test
    fun `several linked domains on a vps survive a markdown export and re-import`() {
        val host = VpsHost(
            id = "v1", tenant = "acme", hostname = "dns", ipv4 = "198.51.100.10", ipv6 = "2001:db8::10",
            specs = "40Gx2G", linkedDomain = "a.example.com, b.example.org", createdAt = 1L, modifiedAt = 1L
        )
        val parsed = VpsMarkdownImportExport.parse(VpsMarkdownImportExport.export(listOf(host))).hosts.single()
        assertEquals("a.example.com, b.example.org", parsed.linkedDomain)
        assertEquals(listOf("198.51.100.10", "2001:db8::10", "acme", "dns"), listOf(parsed.ipv4, parsed.ipv6, parsed.tenant, parsed.hostname))
    }
}
