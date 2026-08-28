package io.github.tabssh.tracker

import org.junit.Test
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
        ## Tenant   - hosteons.com
        dns         - 82.29.128.43    - 2402:d0c0:12:47ab::1                       [40Gx2G]    [dns.casjaydns.com]  [June 7th, 2027, biennially $79.99]  [primary dns/backup mx]
        dns1        - 103.124.104.139 - 2402:d0c0:15:87::f7f3:b529                 [40Gx2G]    [dns1.casjaydns.com] [June 7th, 2027, biennially $79.99]  [backup dns/mx]
        ns          - 82.29.128.140   - 2402:d0c0:12:b52::1                        [40Gx2G]    [casjaydns.fyi]      [June 9th, 2027, annually $29.99]  [technitium dns]
        ----------------------------------------------

        ## Tenant   - interserver.net
        apis        - 104.218.50.148  - 2604:a00:11:3305:216:3eff:fe36:1ffe        [16T/32G]   [apis.apimgr.us]     [August  10 monthly  $48.00]  [TBD]
        ----------------------------------------------

        ## Tenant   - racknerd.com
        mail        - 66.63.179.3     -                                            [100Gx5G]   [casjay.email]       [May  15, 2027, biennially $109.48] [mail server]
        pbx         - 23.238.70.236   - 2607:9d00:2000:0145::27d8:f296             [150Gx8G]   [casjay.tel]         [November 21, 2027, biennially $124.48] [PBX/Fax server]
        ----------------------------------------------

        ## Tenant   - ssdnodes.com
        hosting     - 104.225.216.132 - 2602:ff16:3:12eb::1                        [160Gx8G]   [casjay.xyz]         [June 04, 2028, triennially $252.00]  [hosting server]
        pve         - 23.227.180.26   - 2604:4500:000a:048d:0000:0000:0000:0002    [480Gx64G]  [casjayvps.us]       [June 05, 2028, triennially $36.00]   [proxmox virtualization host]
        ---------------------------------------------

        ## Tenant   - cloud.oracle.com
        ip          - 132.226.33.75   - 2603:c020:4005:4d00:6e70:8e31:88e9:7a42    [50Gx1G]    [ifcfg.us]           [Free/Never]         [IP detection service]
        ---------------------------------------------
        ```
    """.trimIndent()

    @Test
    fun `parses every host row across every tenant block`() {
        val result = VpsMarkdownImportExport.parse(sample)
        assertEquals(9, result.hosts.size, "warnings: ${result.warnings}")
    }

    @Test
    fun `recognizes biennially and triennially billing cycles`() {
        val hosts = VpsMarkdownImportExport.parse(sample).hosts
        val dns = hosts.single { it.hostname == "dns" }
        assertEquals("biennially", dns.billingCycle)
        assertEquals("$79.99", dns.price)

        val hosting = hosts.single { it.hostname == "hosting" }
        assertEquals("triennially", hosting.billingCycle)
        assertEquals("$252.00", hosting.price)

        val ns = hosts.single { it.hostname == "ns" }
        assertEquals("yearly", ns.billingCycle)

        val apis = hosts.single { it.hostname == "apis" }
        assertEquals("monthly", apis.billingCycle)
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
        assertEquals("66.63.179.3", mail.ipv4)
        assertEquals(null, mail.ipv6)
    }

    @Test
    fun `Free-Never renewal text yields no parsed date or billing cycle`() {
        val ip = VpsMarkdownImportExport.parse(sample).hosts.single { it.hostname == "ip" }
        assertEquals("Free/Never", ip.renewalRaw)
        assertEquals(null, ip.renewalDate)
        assertEquals(null, ip.billingCycle)
        assertEquals(null, ip.price)
    }

    @Test
    fun `tenant blocks are attributed correctly`() {
        val hosts = VpsMarkdownImportExport.parse(sample).hosts
        assertEquals("hosteons.com", hosts.single { it.hostname == "dns" }.tenant)
        assertEquals("racknerd.com", hosts.single { it.hostname == "pbx" }.tenant)
        assertEquals("ssdnodes.com", hosts.single { it.hostname == "pve" }.tenant)
        assertEquals("cloud.oracle.com", hosts.single { it.hostname == "ip" }.tenant)
    }

    @Test
    fun `export then re-parse round-trips billing cycle and price`() {
        val original = VpsMarkdownImportExport.parse(sample).hosts
        val reparsed = VpsMarkdownImportExport.parse(VpsMarkdownImportExport.export(original)).hosts
        val dns = reparsed.single { it.hostname == "dns" }
        assertEquals("biennially", dns.billingCycle)
        assertTrue(dns.price == "$79.99")
    }
}
