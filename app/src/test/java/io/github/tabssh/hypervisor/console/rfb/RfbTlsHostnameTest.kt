package io.github.tabssh.hypervisor.console.rfb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RfbTlsHostnameTest {
    @Test
    fun `identifies address literals so TLS does not send invalid DNS SNI`() {
        assertTrue(RfbClient.isIpAddressLiteral("192.168.1.20"))
        assertTrue(RfbClient.isIpAddressLiteral("2001:db8::5"))
        assertTrue(RfbClient.isIpAddressLiteral("[2001:db8::5]"))
        assertFalse(RfbClient.isIpAddressLiteral("proxmox.example.test"))
        assertFalse(RfbClient.isIpAddressLiteral("999.1.1.1"))
    }
}
