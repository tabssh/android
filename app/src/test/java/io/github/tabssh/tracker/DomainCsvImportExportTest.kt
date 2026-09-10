package io.github.tabssh.tracker

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression coverage for [DomainCsvImportExport]. The exact CSV column
 * headers and date formats used by GoDaddy/Porkbun/other registrars are not
 * publicly documented, so this exercises the header-synonym matcher and
 * multi-format date parser generically (different header text/order/date
 * shape than NameCheap's) rather than asserting one specific registrar's
 * literal export — the goal is that any reasonably-shaped domain-list CSV
 * with a recognizable header row imports correctly, not just NameCheap's.
 */
class DomainCsvImportExportTest {

    @Test
    fun `parses the NameCheap format unchanged`() {
        val csv = """
            Domain Name,Domain privacy protection status,Domain status at NC,Domain auto-renew status,Domain expiration date
            amd64.us,OFF,Active,ON,Dec 16 2026
        """.trimIndent()
        val result = DomainCsvImportExport.parse(csv)
        assertEquals(1, result.domains.size, "warnings: ${result.warnings}")
        val d = result.domains.single()
        assertEquals("amd64.us", d.domainName)
        assertEquals("OFF", d.privacyProtection)
        assertEquals("Active", d.statusAtRegistrar)
        assertEquals("ON", d.autoRenew)
        assertEquals("Dec 16 2026", DomainCsvImportExport.export(listOf(d)).lines()[1].split(",").last())
    }

    @Test
    fun `parses a header with reordered columns and slash date format`() {
        val csv = """
            Domain,Expires,Auto Renew,Privacy,Status
            example.com,12/16/2026,Yes,On,Active
        """.trimIndent()
        val result = DomainCsvImportExport.parse(csv)
        assertEquals(1, result.domains.size, "warnings: ${result.warnings}")
        val d = result.domains.single()
        assertEquals("example.com", d.domainName)
        assertEquals("Yes", d.autoRenew)
        assertEquals("On", d.privacyProtection)
        assertEquals("Active", d.statusAtRegistrar)
        assertEquals(true, d.expirationDate != null)
    }

    @Test
    fun `parses a header using ISO date and different synonym names`() {
        val csv = """
            domain name,expiration date,auto-renew,whois privacy,domain lock status
            porkbun-example.com,2027-03-05,ON,ON,Locked
        """.trimIndent()
        val result = DomainCsvImportExport.parse(csv)
        assertEquals(1, result.domains.size, "warnings: ${result.warnings}")
        val d = result.domains.single()
        assertEquals("porkbun-example.com", d.domainName)
        assertEquals("Locked", d.statusAtRegistrar)
        assertEquals(true, d.expirationDate != null)
    }

    @Test
    fun `missing optional columns import as blank fields, privacy defaults to N-A`() {
        val csv = """
            Domain Name,Expiration Date
            minimal.example,2026-01-01
        """.trimIndent()
        val result = DomainCsvImportExport.parse(csv)
        val d = result.domains.single()
        assertEquals("N/A", d.privacyProtection)
        assertEquals("", d.statusAtRegistrar)
        assertEquals("", d.autoRenew)
        assertEquals(true, d.expirationDate != null)
    }

    @Test
    fun `imports the 4-column format with no privacy column, defaulting privacy to N-A`() {
        val csv = """
            Domain Name,Domain status,Domain auto-renew status,Domain expiration date
            fourcol.example,Active,ON,Dec 16 2026
        """.trimIndent()
        val result = DomainCsvImportExport.parse(csv)
        assertEquals(1, result.domains.size, "warnings: ${result.warnings}")
        val d = result.domains.single()
        assertEquals("fourcol.example", d.domainName)
        assertEquals("Active", d.statusAtRegistrar)
        assertEquals("ON", d.autoRenew)
        assertEquals("N/A", d.privacyProtection)
        assertEquals(true, d.expirationDate != null)
    }

    @Test
    fun `export always writes the 5-column NameCheap shape regardless of the imported format`() {
        val csv = """
            Domain Name,Domain status,Domain auto-renew status,Domain expiration date
            fourcol.example,Active,ON,Dec 16 2026
        """.trimIndent()
        val domains = DomainCsvImportExport.parse(csv).domains
        val exported = DomainCsvImportExport.export(domains)
        assertEquals(DomainCsvImportExport.HEADER, exported.lines()[0])
        assertEquals("fourcol.example,N/A,Active,ON,Dec 16 2026", exported.lines()[1])
    }

    @Test
    fun `unrecognized header falls back to the fixed 5-column NameCheap order`() {
        val csv = """
            fallback.example,OFF,Active,ON,Dec 16 2026
        """.trimIndent()
        val result = DomainCsvImportExport.parse(csv)
        val d = result.domains.single()
        assertEquals("fallback.example", d.domainName)
        assertEquals("ON", d.autoRenew)
    }

    @Test
    fun `unparseable expiration date yields a warning and null date rather than throwing`() {
        val csv = """
            Domain Name,Expiration Date
            bad-date.example,not-a-date
        """.trimIndent()
        val result = DomainCsvImportExport.parse(csv)
        val d = result.domains.single()
        assertNull(d.expirationDate)
        assertEquals(true, result.warnings.any { it.contains("bad-date.example") || it.contains("not-a-date") })
    }
}
