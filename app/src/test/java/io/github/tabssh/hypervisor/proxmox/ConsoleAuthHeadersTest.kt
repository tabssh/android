package io.github.tabssh.hypervisor.proxmox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Console WebSocket upgrades authenticate with the API token header for tokens, else the ticket cookie. */
class ConsoleAuthHeadersTest {

    @Test
    fun `a token profile sends the token header and never an empty cookie`() {
        val client = ProxmoxApiClient("pve", 8006, "root@pam!tabssh", "secret", "pam", authType = "proxmox_api_token")
        ProxmoxApiClient::class.java.getDeclaredField("apiTokenAuthorization").apply {
            isAccessible = true
            set(client, "PVEAPIToken=root@pam!tabssh=secret")
        }
        assertTrue(client.usesApiToken)
        assertEquals(mapOf("Authorization" to "PVEAPIToken=root@pam!tabssh=secret"), client.consoleAuthHeaders())
    }

    @Test
    fun `a password profile sends the ticket cookie`() {
        val client = ProxmoxApiClient("pve", 8006, "root", "pw", "pam")
        assertFalse(client.usesApiToken)
        assertTrue(client.consoleAuthHeaders().keys == setOf("Cookie"))
    }
}
