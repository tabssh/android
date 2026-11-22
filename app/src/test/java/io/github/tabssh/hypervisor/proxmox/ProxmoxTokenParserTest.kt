package io.github.tabssh.hypervisor.proxmox

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxmoxTokenParserTest {

    @Test
    fun `a full token splits into the token user and the secret`() {
        val p = ProxmoxTokenParser.parse("root@pam!tabssh:1a2b3c4d-0000-1111-2222-333344445555")
        assertEquals("root@pam!tabssh", p?.tokenUser)
        assertEquals("1a2b3c4d-0000-1111-2222-333344445555", p?.secret)
    }

    @Test
    fun `an equals sign separator is accepted`() {
        assertEquals("secret", ProxmoxTokenParser.parse("u@pve!id=secret")?.secret)
    }

    @Test
    fun `passwords and partial values are not mistaken for a token`() {
        assertNull(ProxmoxTokenParser.parse("hunter2"))
        assertNull(ProxmoxTokenParser.parse("root@pam!tabssh"))
        assertNull(ProxmoxTokenParser.parse("root@pam:pw"))
    }

    @Test
    fun `normalise reads the token from the secret or the user name field`() {
        assertEquals("root@pam!t" to "s3", ProxmoxTokenParser.normalise("", "root@pam!t:s3"))
        assertEquals("root@pam!t" to "s3", ProxmoxTokenParser.normalise("root@pam!t:s3", ""))
        assertEquals("root@pam!t" to "typed", ProxmoxTokenParser.normalise("root@pam!t", "typed"))
    }
}
