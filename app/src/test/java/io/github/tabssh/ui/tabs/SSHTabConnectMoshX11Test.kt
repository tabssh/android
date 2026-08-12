package io.github.tabssh.ui.tabs

import android.content.Context
import com.jcraft.jsch.Channel
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.terminal.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.assertTrue

/**
 * Regression coverage for SSHTab.connectMosh's post-handoff bootstrap
 * session decision. Mosh only ever carries terminal I/O over its own UDP
 * transport — never X11 — so the bootstrap SSHConnection used to always be
 * dropped right after handoff (`connection = null`), silently discarding
 * any X11 forwarding the profile requested. Fixed by branching on
 * [SSHConnection.wantsX11Forwarding]: retain the bootstrap session and open
 * an X11 carrier channel on it when wanted, disconnect it outright
 * otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SSHTabConnectMoshX11Test {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun profile() = ConnectionProfile(
        name = "mosh-x11-test",
        host = "test.example.com",
        username = "user"
    )

    private fun mockedTermuxBridge(): TermuxBridge {
        val bridge = mock(TermuxBridge::class.java)
        `when`(bridge.connectMoshClient(any(), anyString(), anyInt(), anyString())).thenReturn(true)
        return bridge
    }

    @Test
    fun `opens an X11 carrier channel on the bootstrap session when X11 forwarding is wanted`() = runBlocking {
        val bootstrap = mock(SSHConnection::class.java)
        `when`(bootstrap.wantsX11Forwarding()).thenReturn(true)
        `when`(bootstrap.openX11CarrierChannel()).thenReturn(mock(Channel::class.java))

        val tab = SSHTab(profile(), mockedTermuxBridge())
        tab.connection = bootstrap

        val ok = tab.connectMosh(mock(Context::class.java), "test.example.com", 60001, "key==")

        assertTrue(ok)
        verify(bootstrap).openX11CarrierChannel()
        // The retained bootstrap session must stay up for the tab's
        // lifetime — connectMosh itself must not disconnect it.
        verify(bootstrap, never()).disconnect()
    }

    @Test
    fun `never opens an X11 carrier channel when the profile does not want X11 forwarding`() = runBlocking {
        val bootstrap = mock(SSHConnection::class.java)
        `when`(bootstrap.wantsX11Forwarding()).thenReturn(false)
        // Not retained, so connectMosh disconnects it via
        // SSHTab.disconnectBootstrapSession, which reads bootstrap.context
        // to find the process-lifetime scope to dispatch on.
        `when`(bootstrap.context).thenReturn(mock(Context::class.java))

        val tab = SSHTab(profile(), mockedTermuxBridge())
        tab.connection = bootstrap

        val ok = tab.connectMosh(mock(Context::class.java), "test.example.com", 60001, "key==")

        assertTrue(ok)
        verify(bootstrap, never()).openX11CarrierChannel()
    }
}
