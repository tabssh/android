package io.github.tabssh.ssh.connection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.entities.ConnectionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for [SSHConnection.wantsX11Forwarding] — the per-profile
 * eligibility check SSHTab.connectMosh() uses to decide whether a mosh
 * tab's bootstrap SSHConnection must be retained (to carry an x11-req
 * channel) instead of being disconnected right after handoff. Guards
 * against silently drifting from [SSHConnection]'s private
 * `applyForwardingFlags` eligibility computation it mirrors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SSHConnectionX11ForwardingTest {

    private fun newConnection(x11Forwarding: Boolean): SSHConnection {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val profile = ConnectionProfile(
            id = "x11-test",
            name = "X11 Test Server",
            host = "test.example.com",
            port = 22,
            username = "testuser",
            authType = AuthType.PASSWORD.name,
            x11Forwarding = x11Forwarding
        )
        val scope = CoroutineScope(SupervisorJob())
        return SSHConnection(profile, scope, context)
    }

    @Test
    fun `wantsX11Forwarding is true when the profile enables X11 forwarding`() {
        val connection = newConnection(x11Forwarding = true)
        assertTrue(connection.wantsX11Forwarding())
    }

    @Test
    fun `wantsX11Forwarding is false when the profile does not enable X11 forwarding`() {
        val connection = newConnection(x11Forwarding = false)
        assertFalse(connection.wantsX11Forwarding())
    }
}
