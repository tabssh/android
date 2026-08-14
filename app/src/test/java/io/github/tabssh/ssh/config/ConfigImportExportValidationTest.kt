package io.github.tabssh.ssh.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transport-audit regression tests for the untrusted-config paths.
 *
 * Imported `ssh_config` / CSV / JSON / PuTTY / Terraform files and exported
 * configs are all attacker-influenceable: a config can arrive via a share
 * sheet or cloud sync, and an exported file is written from profile rows that
 * came from one. Both directions previously trusted their input — ports were
 * taken verbatim and export emitted values raw onto directive lines.
 */
class ConfigImportExportValidationTest {

    @Test
    fun `bulk import clamps ports outside the TCP range`() {
        assertEquals(22, BulkImportParser.sanitisePort(0))
        assertEquals(22, BulkImportParser.sanitisePort(-1))
        assertEquals(22, BulkImportParser.sanitisePort(65_536))
        assertEquals(22, BulkImportParser.sanitisePort(null))
        assertEquals(2222, BulkImportParser.sanitisePort(2222))
        assertEquals(65_535, BulkImportParser.sanitisePort(65_535))
    }

    @Test
    fun `bulk import accepts hostnames and IP literals`() {
        assertTrue(BulkImportParser.isValidHostValue("example.com"))
        assertTrue(BulkImportParser.isValidHostValue("192.0.2.10"))
        assertTrue(BulkImportParser.isValidHostValue("[2001:db8::1]"))
    }

    @Test
    fun `bulk import rejects hosts that would corrupt a stored profile`() {
        assertFalse(BulkImportParser.isValidHostValue(""))
        assertFalse(BulkImportParser.isValidHostValue("   "))
        assertFalse(BulkImportParser.isValidHostValue("host name"))
        assertFalse(BulkImportParser.isValidHostValue("evil.example.com\n    ProxyCommand id"))
        assertFalse(BulkImportParser.isValidHostValue("a".repeat(256)))
    }

    @Test
    fun `csv import drops a malformed host row and clamps its port`() {
        val csv = """
            name,host,port
            good,good.example.com,2222
            bad,evil host,22
            huge,huge.example.com,99999
        """.trimIndent()
        val result = BulkImportParser.parse(csv)
        assertEquals(BulkImportParser.Format.CSV, result.format)
        assertEquals(listOf("good.example.com", "huge.example.com"), result.hosts.map { it.host })
        assertEquals(listOf(2222, 22), result.hosts.map { it.port })
        assertTrue(result.warnings.any { it.contains("malformed") })
    }

    @Test
    fun `ssh config parser clamps an out-of-range Port directive`() {
        assertEquals(22, SSHConfigParser.parsePort("0"))
        assertEquals(22, SSHConfigParser.parsePort("70000"))
        assertEquals(22, SSHConfigParser.parsePort("not-a-number"))
        assertEquals(2222, SSHConfigParser.parsePort(" 2222 "))
    }

    @Test
    fun `ssh config parser clamps absurd timeout directives`() {
        assertEquals(15, SSHConfigParser.parseSeconds("-1", 15, 3_600))
        assertEquals(15, SSHConfigParser.parseSeconds("999999", 15, 3_600))
        assertEquals(30, SSHConfigParser.parseSeconds("30", 15, 3_600))
    }

    /**
     * A CR/LF inside any exported value would open a new config line, letting
     * a poisoned profile inject directives such as `ProxyCommand` into a file
     * the user later feeds to real `ssh`.
     */
    @Test
    fun `exporter strips control characters from emitted values`() {
        assertEquals(
            "evil.example.comProxyCommand id",
            SSHConfigExporter.oneLine("evil.example.com\nProxyCommand id")
        )
        assertEquals("plain.example.com", SSHConfigExporter.oneLine("  plain.example.com\r\n"))
    }

    @Test
    fun `exporter quotes host tags with spaces and drops embedded quotes`() {
        assertEquals("\"My Server\"", SSHConfigExporter.sanitiseHostTag("My Server"))
        assertEquals("myserver", SSHConfigExporter.sanitiseHostTag("my\"server"))
        assertEquals("host", SSHConfigExporter.sanitiseHostTag("   "))
        assertFalse(SSHConfigExporter.sanitiseHostTag("a\nHost evil").contains('\n'))
    }
}
