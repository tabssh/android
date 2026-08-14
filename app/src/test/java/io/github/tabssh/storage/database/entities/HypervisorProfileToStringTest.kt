package io.github.tabssh.storage.database.entities

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves [HypervisorProfile.toString] masks the stored `password` instead of
 * printing it verbatim (the compiler-generated data-class toString would
 * leak it into `Logger.d`/exception messages).
 */
class HypervisorProfileToStringTest {

    private fun profile(password: String) = HypervisorProfile(
        id = 1L,
        name = "pve1",
        type = HypervisorType.PROXMOX,
        host = "10.0.0.1",
        port = 8006,
        username = "root",
        password = password,
        realm = "pam"
    )

    @Test
    fun `toString masks a non-empty password`() {
        val rendered = profile("super-secret").toString()

        assertFalse(rendered.contains("super-secret"))
        assertTrue(rendered.contains("password=xxxxx"))
    }

    @Test
    fun `toString reports an empty password as none`() {
        val rendered = profile("").toString()

        assertTrue(rendered.contains("password=<none>"))
    }

    @Test
    fun `toString preserves non-secret fields`() {
        val rendered = profile("super-secret").toString()

        assertTrue(rendered.contains("name=pve1"))
        assertTrue(rendered.contains("host=10.0.0.1"))
        assertTrue(rendered.contains("username=root"))
        assertTrue(rendered.contains("realm=pam"))
    }
}
