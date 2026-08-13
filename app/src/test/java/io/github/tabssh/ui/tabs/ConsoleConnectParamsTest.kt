package io.github.tabssh.ui.tabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConsoleConnectParams] carries the hypervisor password, so its string form
 * must never contain it — a data class's generated toString() printed every
 * property, and this object reaches log lines and crash reports.
 */
class ConsoleConnectParamsTest {

    private fun params() = ConsoleConnectParams(
        type = HypervisorConsoleType.PROXMOX,
        host = "pve.example",
        port = 8006,
        username = "root",
        password = "sup3r-s3cret",
        verifySsl = true,
        pinnedCertSha256 = "aa:bb:cc",
        vmId = "101",
        vmName = "web01",
        vmNode = "pve1",
        vmType = "qemu",
        realm = "pam",
        vmRef = null
    )

    @Test
    fun `toString masks the password`() {
        val text = params().toString()
        assertFalse("password leaked in: $text", text.contains("sup3r-s3cret"))
        assertTrue(text.contains("password=xxxxx"))
    }

    @Test
    fun `toString does not print the pinned certificate fingerprint`() {
        val text = params().toString()
        assertFalse(text.contains("aa:bb:cc"))
        assertTrue(text.contains("pinnedCertSha256=set"))
    }

    @Test
    fun `toString keeps the fields needed for diagnostics`() {
        val text = params().toString()
        assertTrue(text.contains("host=pve.example"))
        assertTrue(text.contains("port=8006"))
        assertTrue(text.contains("username=root"))
        assertTrue(text.contains("vmName=web01"))
    }

    @Test
    fun `equality still compares every field`() {
        assertTrue(params() == params())
        assertFalse(params() == params().copy(password = "other"))
    }
}
