package io.github.tabssh.hypervisor.proxmox

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Proxmox login ticket lasts 2 hours, so it is renewed before then and never renewed while fresh. */
class TicketRenewalTest {

    private val minute = 60_000L

    @Test
    fun `a fresh ticket is left alone`() {
        assertFalse(ProxmoxApiClient.ticketNeedsRenewal(0))
        assertFalse(ProxmoxApiClient.ticketNeedsRenewal(89 * minute))
    }

    @Test
    fun `an ageing ticket is renewed before the two hour expiry`() {
        assertTrue(ProxmoxApiClient.ticketNeedsRenewal(90 * minute))
        assertTrue(ProxmoxApiClient.ticketNeedsRenewal(119 * minute))
    }
}
