package io.github.tabssh.ui.activities

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the container host form validators.
 *
 * Before the fix the host address and the remote path fields were passed to
 * the transport layer unchecked, so a stray space, a newline pasted from a
 * terminal, or a relative path reached remote command construction and
 * produced a confusing failure far from the field that caused it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContainerHostEditValidationTest {

    @Test
    fun `plain hostnames and addresses are accepted`() {
        assertTrue(ContainerHostEditActivity.isValidHostAddress("example.com"))
        assertTrue(ContainerHostEditActivity.isValidHostAddress("192.168.1.10"))
        assertTrue(ContainerHostEditActivity.isValidHostAddress("fe80::1"))
    }

    @Test
    fun `empty host address is rejected`() {
        assertFalse(ContainerHostEditActivity.isValidHostAddress(""))
    }

    @Test
    fun `whitespace and control characters are rejected`() {
        assertFalse(ContainerHostEditActivity.isValidHostAddress("exa mple.com"))
        assertFalse(ContainerHostEditActivity.isValidHostAddress("example.com\n"))
        assertFalse(ContainerHostEditActivity.isValidHostAddress("example\u0000com"))
    }

    @Test
    fun `url and user shaped values are rejected`() {
        assertFalse(ContainerHostEditActivity.isValidHostAddress("http://example.com"))
        assertFalse(ContainerHostEditActivity.isValidHostAddress("root@example.com"))
        assertFalse(ContainerHostEditActivity.isValidHostAddress("example.com\\share"))
    }

    @Test
    fun `over long host addresses are rejected`() {
        assertFalse(ContainerHostEditActivity.isValidHostAddress("a".repeat(256)))
    }

    @Test
    fun `absolute remote paths are accepted`() {
        assertTrue(ContainerHostEditActivity.isValidRemotePath("/var/run/docker.sock"))
        assertTrue(ContainerHostEditActivity.isValidRemotePath("/srv/compose stacks"))
    }

    @Test
    fun `relative and control bearing paths are rejected`() {
        assertFalse(ContainerHostEditActivity.isValidRemotePath("var/run/docker.sock"))
        assertFalse(ContainerHostEditActivity.isValidRemotePath(""))
        assertFalse(ContainerHostEditActivity.isValidRemotePath("/var/run\ndocker.sock"))
        assertFalse(ContainerHostEditActivity.isValidRemotePath("/" + "a".repeat(4096)))
    }
}
