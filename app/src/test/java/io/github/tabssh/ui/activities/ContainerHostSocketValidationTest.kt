package io.github.tabssh.ui.activities

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the container host socket-override validator.
 *
 * The socket field is optional: blank means "probe this engine's default
 * locations" (`ContainerHost.socketCandidates()`). Before the fix the editor
 * substituted the entity default for an empty field and then ran the absolute
 * path check against it — and since that default became blank, every save was
 * rejected with a path error. Blank must be accepted and stored blank.
 *
 * A typed value is accepted in the same three shapes
 * `ContainerHost.usesNetworkEndpoint()` recognises: an absolute unix path,
 * `tcp://host:port`, or `ssh://user@host`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContainerHostSocketValidationTest {

    @Test
    fun `blank is accepted — it means auto-detect for the selected engine`() {
        assertTrue(ContainerHostEditActivity.isValidSocketEndpoint(""))
    }

    @Test
    fun `absolute unix socket paths are accepted`() {
        assertTrue(ContainerHostEditActivity.isValidSocketEndpoint("/var/run/docker.sock"))
        assertTrue(ContainerHostEditActivity.isValidSocketEndpoint("/var/lib/incus/unix.socket"))
        assertTrue(
            ContainerHostEditActivity.isValidSocketEndpoint(
                "/var/snap/lxd/common/lxd/unix.socket"
            )
        )
        assertTrue(ContainerHostEditActivity.isValidSocketEndpoint("/run/podman/podman.sock"))
    }

    @Test
    fun `tcp endpoints are accepted`() {
        assertTrue(ContainerHostEditActivity.isValidSocketEndpoint("tcp://10.0.0.5:2375"))
        assertTrue(ContainerHostEditActivity.isValidSocketEndpoint("tcp://engine.example.com:2376"))
        assertTrue(ContainerHostEditActivity.isValidSocketEndpoint("tcp://[::1]:2375"))
    }

    @Test
    fun `ssh endpoints are accepted, with or without a port`() {
        assertTrue(ContainerHostEditActivity.isValidSocketEndpoint("ssh://deploy@10.0.0.5"))
        assertTrue(ContainerHostEditActivity.isValidSocketEndpoint("ssh://deploy@host.example.com:2222"))
        assertTrue(ContainerHostEditActivity.isValidSocketEndpoint("ssh://host.example.com"))
    }

    @Test
    fun `a malformed value is rejected`() {
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("not-a-socket"))
    }

    @Test
    fun `a relative or scheme-less path is rejected`() {
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("var/run/docker.sock"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("./docker.sock"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("http://10.0.0.5:2375"))
    }

    @Test
    fun `a tcp endpoint without a usable port is rejected`() {
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("tcp://10.0.0.5"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("tcp://10.0.0.5:0"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("tcp://10.0.0.5:70000"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("tcp://10.0.0.5:docker"))
    }

    @Test
    fun `an endpoint with no host is rejected`() {
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("tcp://"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("tcp://:2375"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("ssh://"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("ssh://@host.example.com"))
    }

    @Test
    fun `a path smuggled onto an endpoint is rejected`() {
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("tcp://10.0.0.5:2375/v1.43"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("ssh://deploy@host/../etc"))
    }

    @Test
    fun `whitespace and control characters are rejected`() {
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("/var/run/docker sock"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("/var/run/docker.sock\nrm -rf /"))
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("tcp://10.0.0.5:2375 "))
    }

    @Test
    fun `an over-long value is rejected`() {
        assertFalse(ContainerHostEditActivity.isValidSocketEndpoint("/" + "a".repeat(4096)))
    }
}
