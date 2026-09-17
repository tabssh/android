package io.github.tabssh.tracker

import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for [VpsMarkdownImportExport], using a real-shaped
 * `VPS.md` sample (grouped-by-tenant blocks, alignment padding, biennial/
 * triennial billing cycles) to catch parser regressions on this format.
 */
class VpsMarkdownImportExportTest {

    private val sample = """
        # My VPS HOST MAPPINGS

        ```text
        ## Tenant   - tenant-one.example
        dns         - 198.51.100.10   - 2001:db8:12:47ab::1                       [40Gx2G]    [dns.example.com]    [June 7th, 2027, biennially $79.99]  [primary dns/backup mx]
        dns1        - 198.51.100.11   - 2001:db8:15:87::f7f3:b529                 [40Gx2G]    [dns1.example.com]   [June 7th, 2027, biennially $79.99]  [backup dns/mx]
        ns          - 198.51.100.12   - 2001:db8:12:b52::1                        [40Gx2G]    [ns.example.org]     [June 9th, 2027, annually $29.99]  [technitium dns]
        ----------------------------------------------

        ## Tenant   - tenant-two.example
        apis        - 198.51.100.20   - 2001:db8:11:3305:216:3eff:fe36:1ffe       [16T/32G]   [apis.example.net]   [August  10 monthly  $48.00]  [TBD]
        ----------------------------------------------

        ## Tenant   - tenant-three.example
        mail        - 198.51.100.30   -                                            [100Gx5G]   [mail.example.com]   [May  15, 2027, biennially $109.48] [mail server]
        pbx         - 198.51.100.31   - 2001:db8:2000:0145::27d8:f296             [150Gx8G]   [pbx.example.net]    [November 21, 2027, biennially $124.48] [PBX/Fax server]
        ----------------------------------------------

        ## Tenant   - tenant-four.example
        hosting     - 198.51.100.40   - 2001:db8:3:12eb::1                        [160Gx8G]   [hosting.example.org] [June 04, 2028, triennially $252.00]  [hosting server]
        pve         - 198.51.100.41   - 2001:db8:a:48d:0000:0000:0000:0002        [480Gx64G]  [pve.example.com]    [June 05, 2028, triennially $36.00]   [proxmox virtualization host]
        ---------------------------------------------

        ## Tenant   - tenant-five.example
        ip          - 198.51.100.50   - 2001:db8:4005:4d00:6e70:8e31:88e9:7a42    [50Gx1G]    [example.invalid]    [Free/Never]         [IP detection service]
        ---------------------------------------------
        ```
    """.trimIndent()

    @Test
    fun `parses every host row across every tenant block`() {
        val result = VpsMarkdownImportExport.parse(sample)
        assertEquals(9, result.hosts.size, "warnings: ${result.warnings}")
    }

    @Test
    fun `stores biennially, triennially, annually and monthly as canonical cycles`() {
        val hosts = VpsMarkdownImportExport.parse(sample).hosts
        val dns = hosts.single { it.hostname == "dns" }
        assertEquals("2 years", dns.billingCycle)
        assertEquals("$79.99", dns.price)

        val hosting = hosts.single { it.hostname == "hosting" }
        assertEquals("3 years", hosting.billingCycle)
        assertEquals("$252.00", hosting.price)

        val ns = hosts.single { it.hostname == "ns" }
        assertEquals("1 year", ns.billingCycle)

        val apis = hosts.single { it.hostname == "apis" }
        assertEquals("1 month", apis.billingCycle)
    }

    @Test
    fun `parses exact renewal dates including biennial and triennial rows`() {
        val hosts = VpsMarkdownImportExport.parse(sample).hosts
        val dns = hosts.single { it.hostname == "dns" }
        assertEquals("June 7, 2027", VpsMarkdownImportExport.formatRenewalDate(requireNotNull(dns.renewalDate)))

        val hosting = hosts.single { it.hostname == "hosting" }
        assertEquals("June 4, 2028", VpsMarkdownImportExport.formatRenewalDate(requireNotNull(hosting.renewalDate)))
    }

    @Test
    fun `handles rows with a blank ipv6 field`() {
        val mail = VpsMarkdownImportExport.parse(sample).hosts.single { it.hostname == "mail" }
        assertEquals("198.51.100.30", mail.ipv4)
        assertEquals(null, mail.ipv6)
    }

    @Test
    fun `Free-Never renewal renews yearly on the next January 1st`() {
        val ip = VpsMarkdownImportExport.parse(sample).hosts.single { it.hostname == "ip" }
        val expected = RenewalDateInput.nextJanuaryFirst()
        assertEquals(RenewalDateInput.format(expected), ip.renewalRaw)
        assertEquals(expected, ip.renewalDate)
        assertEquals("1 year", ip.billingCycle)
        assertEquals(null, ip.price)
    }

    @Test
    fun `tenant blocks are attributed correctly`() {
        val hosts = VpsMarkdownImportExport.parse(sample).hosts
        assertEquals("tenant-one.example", hosts.single { it.hostname == "dns" }.tenant)
        assertEquals("tenant-three.example", hosts.single { it.hostname == "pbx" }.tenant)
        assertEquals("tenant-four.example", hosts.single { it.hostname == "pve" }.tenant)
        assertEquals("tenant-five.example", hosts.single { it.hostname == "ip" }.tenant)
    }

    @Test
    fun `year-less monthly cycle anchors to the day-of-month, not a yearly projection`() {
        // "August 10 monthly" has no year — the parser used to always
        // project year-less month/day text forward by a whole year
        // regardless of billing cycle, so a monthly host's due date landed
        // up to a year out instead of on the 10th of the correct month.
        val apis = VpsMarkdownImportExport.parse(sample).hosts.single { it.hostname == "apis" }
        val renewalDate = requireNotNull(apis.renewalDate)
        // renewalDate is always anchored in UTC (see VpsMarkdownImportExport),
        // so it must be read back in UTC too — a local-timezone Calendar would
        // read the wrong day-of-month whenever the device's offset crosses
        // local midnight, which is exactly the class of bug this test guards.
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = renewalDate
        assertEquals(10, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(true, renewalDate >= System.currentTimeMillis())
    }

    @Test
    fun `recognizes biannually and triannually as synonyms for every-2 and every-3 years`() {
        val text = """
            ## Tenant   - tenant-synonym.example
            dns  - 198.51.100.60 - [40Gx2G] [dns.example.com] [June 7th, 2027, biannually ${'$'}79.99] [note]
            svc  - 198.51.100.61 - [40Gx2G] [svc.example.com] [June 7th, 2027, triannually ${'$'}12.00] [note]
        """.trimIndent()
        val hosts = VpsMarkdownImportExport.parse(text).hosts
        assertEquals("2 years", hosts.single { it.hostname == "dns" }.billingCycle)
        assertEquals("3 years", hosts.single { it.hostname == "svc" }.billingCycle)
    }

    @Test
    fun `recognizes an arbitrary N years cycle`() {
        val text = """
            ## Tenant   - tenant-nyear.example
            vps5  - 198.51.100.70 - [40Gx2G] [vps5.example.com] [June 9th, 2026, 5 years ${'$'}299.99] [note]
            vps10 - 198.51.100.71 - [40Gx2G] [vps10.example.com] [June 9th, 2026, 10 years ${'$'}499.99] [note]
        """.trimIndent()
        val hosts = VpsMarkdownImportExport.parse(text).hosts
        assertEquals("5 years", hosts.single { it.hostname == "vps5" }.billingCycle)
        assertEquals("10 years", hosts.single { it.hostname == "vps10" }.billingCycle)
    }

    @Test
    fun `export then re-parse round-trips billing cycle and price`() {
        val original = VpsMarkdownImportExport.parse(sample).hosts
        val reparsed = VpsMarkdownImportExport.parse(VpsMarkdownImportExport.export(original)).hosts
        val dns = reparsed.single { it.hostname == "dns" }
        assertEquals("2 years", dns.billingCycle)
        assertTrue(dns.price == "$79.99")
    }

    @Test
    fun `renewal text stores the date only, never the billing cycle`() {
        val hosts = VpsMarkdownImportExport.parse(sample).hosts
        // The cycle is its own column; repeating it inside the date text is
        // what made the tracker render "June 7, 2027, biennially".
        assertEquals("June 7, 2027", hosts.single { it.hostname == "dns" }.renewalRaw)
        assertEquals("June 4, 2028", hosts.single { it.hostname == "hosting" }.renewalRaw)
        val apis = hosts.single { it.hostname == "apis" }
        assertEquals(RenewalDateInput.format(requireNotNull(apis.renewalDate)), apis.renewalRaw)
    }

    @Test
    fun `splitRenewal leaves text with no recognizable cycle untouched`() {
        val plain = VpsMarkdownImportExport.splitRenewal("April 30, 2027")
        assertEquals("April 30, 2027", plain.dateText)
        assertEquals(null, plain.billingCycle)

        val freeform = VpsMarkdownImportExport.splitRenewal("Free/Never")
        assertEquals("Free/Never", freeform.dateText)
        assertEquals(null, freeform.billingCycle)

        val blank = VpsMarkdownImportExport.splitRenewal(null)
        assertEquals(null, blank.dateText)
        assertEquals(null, blank.billingCycle)
    }

    @Test
    fun `splitRenewal strips the cycle and tidies what it leaves behind`() {
        val trailing = VpsMarkdownImportExport.splitRenewal("April 30, 2027, triennially")
        assertEquals("April 30, 2027", trailing.dateText)
        assertEquals("3 years", trailing.billingCycle)

        // "biannually" must not be read as the "annually" it ends with.
        val misspelled = VpsMarkdownImportExport.splitRenewal("June 7, 2027, biannually")
        assertEquals("June 7, 2027", misspelled.dateText)
        assertEquals("2 years", misspelled.billingCycle)

        val everyN = VpsMarkdownImportExport.splitRenewal("June 7, 2030, every 5 years")
        assertEquals("June 7, 2030", everyN.dateText)
        assertEquals("5 years", everyN.billingCycle)

        // A field that is nothing but a cycle leaves no date behind.
        val cycleOnly = VpsMarkdownImportExport.splitRenewal("monthly")
        assertEquals(null, cycleOnly.dateText)
        assertEquals("1 month", cycleOnly.billingCycle)

        val anually = VpsMarkdownImportExport.splitRenewal("May 15, 2027, anually")
        assertEquals("May 15, 2027", anually.dateText)
        assertEquals("1 year", anually.billingCycle)

        val timesPer = VpsMarkdownImportExport.splitRenewal("May 15, 2027, 6 times a year")
        assertEquals("May 15, 2027", timesPer.dateText)
        assertEquals("6 times a year", timesPer.billingCycle)
    }

    @Test
    fun `normalizeRenewal canonicalizes date text and keeps unreadable text dateless`() {
        val now = 1_800_000_000_000L
        val strict = VpsMarkdownImportExport.normalizeRenewal("2027, May 15", "yearly", now)
        assertEquals("May 15, 2027", strict.renewalRaw)
        assertEquals("1 year", strict.billingCycle)

        val free = VpsMarkdownImportExport.normalizeRenewal("N/A", null, now)
        assertEquals(RenewalDateInput.nextJanuaryFirst(now), free.renewalDate)
        assertEquals("1 year", free.billingCycle)

        val tbd = VpsMarkdownImportExport.normalizeRenewal("TBD", "10 years", now)
        assertEquals("TBD", tbd.renewalRaw)
        assertEquals(null, tbd.renewalDate)
        assertEquals("10 years", tbd.billingCycle)
    }

    @Test
    fun `export re-attaches the billing cycle it stripped on import`() {
        val exported = VpsMarkdownImportExport.export(VpsMarkdownImportExport.parse(sample).hosts)
        assertTrue(
            exported.contains("[June 7, 2027, Every 2 years \$79.99]"),
            "cycle missing from exported renewal field:\n$exported"
        )
    }
}
