package io.github.tabssh.tracker

import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.storage.database.entities.VpsHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Re-importing a tracker file updates rows already tracked instead of duplicating them. */
class TrackerImportMergeTest {

    private fun domain(
        id: String, name: String, expires: Long? = 1000L, notes: String? = null,
        reminded: Long? = null, canceled: Long? = null, days: Int = 7, status: String = "Active"
    ) = Domain(
        id = id, domainName = name, privacyProtection = "ON", statusAtRegistrar = status, autoRenew = "ON",
        expirationDate = expires, reminderDaysBefore = days, lastReminderSentAt = reminded, notes = notes,
        canceledAt = canceled, createdAt = 1L, modifiedAt = 1L
    )

    private fun vps(
        id: String, host: String = "web", tenant: String = "acme", v4: String? = null, v6: String? = null,
        renewal: Long? = 5000L, reminded: Long? = null, canceled: Long? = null, specs: String? = null
    ) = VpsHost(
        id = id, tenant = tenant, hostname = host, ipv4 = v4, ipv6 = v6, specs = specs, renewalDate = renewal,
        reminderDaysBefore = 3, lastReminderSentAt = reminded, canceledAt = canceled, createdAt = 1L, modifiedAt = 1L
    )

    @Test
    fun `a domain already tracked is updated in place whatever its letter case`() {
        val existing = listOf(domain("a", "Example.com", notes = "mine", days = 14, status = "Active"))
        val out = TrackerImportMerge.mergeDomains(listOf(domain("new", "example.COM", status = "Expired")), existing)
        assertEquals(0, out.added)
        assertEquals(1, out.updated)
        val row = out.rows.single()
        assertEquals("a", row.id)
        assertEquals("Expired", row.statusAtRegistrar)
        assertEquals("mine", row.notes)
        assertEquals(14, row.reminderDaysBefore)
    }

    @Test
    fun `a new expiry date restarts the reminder cycle`() {
        val existing = listOf(domain("a", "a.com", expires = 1000L, reminded = 900L))
        val renewed = TrackerImportMerge.mergeDomains(listOf(domain("x", "a.com", expires = 2000L)), existing).rows.single()
        assertNull(renewed.lastReminderSentAt)
        val same = TrackerImportMerge.mergeDomains(listOf(domain("x", "a.com", expires = 1000L)), existing).rows.single()
        assertEquals(900L, same.lastReminderSentAt)
    }

    @Test
    fun `a canceled domain stays canceled after a re-import`() {
        val existing = listOf(domain("a", "a.com", canceled = 55L))
        assertEquals(55L, TrackerImportMerge.mergeDomains(listOf(domain("x", "a.com")), existing).rows.single().canceledAt)
    }

    @Test
    fun `the same domain twice in one file is added once with the later values`() {
        val out = TrackerImportMerge.mergeDomains(
            listOf(domain("x", "b.com", status = "Old"), domain("y", "B.com", status = "New")), emptyList()
        )
        assertEquals(1, out.added)
        assertEquals(0, out.updated)
        assertEquals("New", out.rows.single().statusAtRegistrar)
    }

    @Test
    fun `a vps host is matched on its ipv4 even when it was renamed`() {
        val existing = listOf(vps("a", host = "old", v4 = "198.51.100.10", specs = "1G"))
        val out = TrackerImportMerge.mergeVps(listOf(vps("n", host = "new", v4 = "198.51.100.10", specs = "2G")), existing)
        assertEquals(1, out.updated)
        assertEquals(0, out.added)
        assertEquals("a", out.rows.single().id)
        assertEquals("new", out.rows.single().hostname)
        assertEquals("2G", out.rows.single().specs)
    }

    @Test
    fun `a vps host with a new ipv4 is matched on its ipv6`() {
        val existing = listOf(vps("a", v4 = "198.51.100.10", v6 = "2001:db8::1"))
        val out = TrackerImportMerge.mergeVps(
            listOf(vps("n", v4 = "198.51.100.99", v6 = "2001:0DB8:0:0:0:0:0:1")), existing
        )
        assertEquals(1, out.updated)
        assertEquals(1, out.rows.size)
        assertEquals("198.51.100.99", out.rows.single().ipv4)
    }

    @Test
    fun `an unknown address is a new host even if the hostname repeats`() {
        val existing = listOf(vps("a", v4 = "198.51.100.10"))
        val out = TrackerImportMerge.mergeVps(listOf(vps("n", v4 = "203.0.113.5")), existing)
        assertEquals(1, out.added)
        assertEquals(2, out.rows.size)
    }

    @Test
    fun `a host with no address falls back to tenant and hostname`() {
        val existing = listOf(vps("a", tenant = "Acme", host = "Web"))
        val out = TrackerImportMerge.mergeVps(listOf(vps("n", tenant = "acme", host = "web", specs = "4G")), existing)
        assertEquals(1, out.updated)
        assertEquals("4G", out.rows.single().specs)
    }

    @Test
    fun `the same host twice in one file is added once and keeps local reminder state`() {
        val twice = TrackerImportMerge.mergeVps(
            listOf(vps("x", v4 = "192.0.2.1", specs = "1G"), vps("y", v4 = "192.0.2.1", specs = "2G")), emptyList()
        )
        assertEquals(1, twice.added)
        assertEquals("2G", twice.rows.single().specs)

        val existing = listOf(vps("a", v4 = "192.0.2.1", renewal = 5000L, reminded = 4000L, canceled = 9L))
        val kept = TrackerImportMerge.mergeVps(listOf(vps("n", v4 = "192.0.2.1", renewal = 5000L)), existing).rows.single()
        assertEquals(4000L, kept.lastReminderSentAt)
        assertEquals(9L, kept.canceledAt)
        val renewed = TrackerImportMerge.mergeVps(listOf(vps("n", v4 = "192.0.2.1", renewal = 9000L)), existing).rows.single()
        assertNull(renewed.lastReminderSentAt)
    }
}
