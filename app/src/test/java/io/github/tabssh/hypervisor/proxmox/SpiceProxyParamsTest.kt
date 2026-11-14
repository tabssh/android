package io.github.tabssh.hypervisor.proxmox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A Proxmox spiceproxy ticket's host is a `pvespiceproxy:...` token reachable only through the
 * returned HTTP proxy, so the connection params must carry that proxy to the SPICE client.
 */
class SpiceProxyParamsTest {

    private fun result(proxy: String?) = ProxmoxApiClient.SPICEProxyResult(
        host = "pvespiceproxy:6af7affa:9001:pve:61002::abc",
        port = 0,
        tlsPort = 61002,
        password = "ticket",
        caCert = null,
        hostSubject = null,
        title = "VM 9001",
        proxy = proxy
    )

    @Test
    fun `the proxy reaches the SPICE connection params`() {
        val params = result("http://pve.example:3128").toConnectionParams()
        assertEquals("http://pve.example:3128", params.proxy)
        assertEquals(61002, params.tlsPort)
    }

    @Test
    fun `no proxy in the API reply means none in the params`() {
        assertNull(result(null).toConnectionParams().proxy)
    }
}
