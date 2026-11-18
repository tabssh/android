package io.github.tabssh.ssh.config

import io.github.tabssh.storage.database.entities.ConnectionProfile
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The exported ssh_config must stay valid OpenSSH config whatever TabSSH-only data a connection carries (tracker
 * links, color tags, sync state): only real directives, a plain alias per Host, and a clean round trip.
 */
class ExportedConfigValidityTest {

    private val directives = setOf(
        "Host", "HostName", "Port", "User", "Compression", "ConnectTimeout", "ForwardX11", "ForwardAgent",
        "IdentityFile", "ProxyJump", "SetEnv", "SendEnv", "RemoteCommand", "RequestTTY"
    )

    private fun conn(name: String, host: String, user: String = "root", port: Int = 22, vps: String? = null) =
        ConnectionProfile(id = name, name = name, host = host, username = user, port = port, vpsLink = vps)

    @Test
    fun `tracker links and other TabSSH-only data never reach the config`() {
        val config = SSHConfigExporter.export(
            listOf(conn("web", "198.51.100.10", vps = "some-vps-id"), conn("db", "db.example.com", user = "admin", port = 2222))
        )
        for (line in config.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            assertTrue(line.substringBefore(' ') in directives, "not an OpenSSH directive: $line")
        }
        assertFalse("some-vps-id" in config)
    }

    @Test
    fun `host aliases cannot become wildcard or negated patterns`() {
        assertEquals("prod_web_", SSHConfigExporter.sanitiseHostTag("prod*web?"))
        assertEquals("staging", SSHConfigExporter.sanitiseHostTag("!staging"))
        assertEquals("\"my web\"", SSHConfigExporter.sanitiseHostTag("my web"))
        val config = SSHConfigExporter.export(listOf(conn("a*b", "a.example.com")))
        assertTrue(config.lines().any { it.trim() == "Host a_b" })
    }

    @Test
    fun `an exported config parses back to the same hosts, users and ports`() {
        val original = listOf(conn("web", "198.51.100.10"), conn("db", "db.example.com", user = "admin", port = 2222))
        val parsed = SSHConfigParser().parseConfig(SSHConfigExporter.export(original))

        assertEquals(listOf("198.51.100.10", "db.example.com"), parsed.map { it.host })
        assertEquals(listOf("root", "admin"), parsed.map { it.username })
        assertEquals(listOf(22, 2222), parsed.map { it.port })
    }
}
