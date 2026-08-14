package io.github.tabssh.ui.activities

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the hypervisor host field validator and the error-detail redactor.
 *
 * The host value is concatenated into the `https://` base URL of the Proxmox /
 * XCP-ng / VMware clients and handed to JSch for libvirt. Before the fix the
 * field accepted anything non-blank, so a scheme, userinfo, path or CR/LF could
 * silently redirect the connection or split the constructed request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HypervisorEditHostValidationTest {

    @Test
    fun `plain hostnames and IPv4 literals are accepted`() {
        assertTrue(HypervisorEditActivity.isValidHostValue("pve.example.com"))
        assertTrue(HypervisorEditActivity.isValidHostValue("192.168.1.10"))
        assertTrue(HypervisorEditActivity.isValidHostValue("esxi-01"))
        assertTrue(HypervisorEditActivity.isValidHostValue("host_name.internal"))
    }

    @Test
    fun `bracketed IPv6 literals are accepted`() {
        assertTrue(HypervisorEditActivity.isValidHostValue("[::1]"))
        assertTrue(HypervisorEditActivity.isValidHostValue("[fe80::1%eth0]"))
    }

    @Test
    fun `unclosed bracket is rejected`() {
        assertFalse(HypervisorEditActivity.isValidHostValue("[::1"))
        assertFalse(HypervisorEditActivity.isValidHostValue("[]"))
    }

    @Test
    fun `a scheme is rejected`() {
        assertFalse(HypervisorEditActivity.isValidHostValue("https://evil.example.com"))
    }

    @Test
    fun `userinfo is rejected`() {
        assertFalse(HypervisorEditActivity.isValidHostValue("pve.example.com@evil.example.com"))
    }

    @Test
    fun `a path query or fragment is rejected`() {
        assertFalse(HypervisorEditActivity.isValidHostValue("pve.example.com/api2/json"))
        assertFalse(HypervisorEditActivity.isValidHostValue("pve.example.com?a=b"))
        assertFalse(HypervisorEditActivity.isValidHostValue("pve.example.com#frag"))
        assertFalse(HypervisorEditActivity.isValidHostValue("pve.example.com\\share"))
    }

    @Test
    fun `a smuggled port is rejected — the port has its own validated field`() {
        assertFalse(HypervisorEditActivity.isValidHostValue("pve.example.com:8006"))
    }

    @Test
    fun `whitespace and CR LF are rejected`() {
        assertFalse(HypervisorEditActivity.isValidHostValue("pve example.com"))
        assertFalse(HypervisorEditActivity.isValidHostValue("pve.example.com\r\nX-Injected: 1"))
        assertFalse(HypervisorEditActivity.isValidHostValue("pve.example.com "))
    }

    @Test
    fun `blank and over-long hosts are rejected`() {
        assertFalse(HypervisorEditActivity.isValidHostValue(""))
        assertFalse(HypervisorEditActivity.isValidHostValue("   "))
        assertFalse(HypervisorEditActivity.isValidHostValue("a".repeat(256)))
    }

    @Test
    fun `safeDetail strips control characters`() {
        assertEquals("bad request", HypervisorEditActivity.safeDetail("bad req\nuest"))
    }

    @Test
    fun `safeDetail strips bidi overrides and isolates`() {
        assertEquals("evilgood", HypervisorEditActivity.safeDetail("evil‮good‬"))
        assertEquals("ab", HypervisorEditActivity.safeDetail("a⁦b⁩"))
    }

    @Test
    fun `safeDetail caps the length`() {
        assertEquals(300, HypervisorEditActivity.safeDetail("x".repeat(5000)).length)
    }

    @Test
    fun `safeDetail falls back for null and blank input`() {
        assertEquals("unknown error", HypervisorEditActivity.safeDetail(null))
        assertEquals("unknown error", HypervisorEditActivity.safeDetail("   "))
        assertEquals("unknown error", HypervisorEditActivity.safeDetail(""))
    }
}
