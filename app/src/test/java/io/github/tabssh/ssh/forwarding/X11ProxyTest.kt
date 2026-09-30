package io.github.tabssh.ssh.forwarding

import android.app.Application
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class X11ProxyTest {
    @Test
    fun `listener is loopback only and repeated start preserves its port`() {
        val proxy = X11Proxy {}
        try {
            proxy.start()
            val field = X11Proxy::class.java.getDeclaredField("serverSocket").apply { isAccessible = true }
            val socket = field.get(proxy) as ServerSocket
            assertTrue(socket.inetAddress.isLoopbackAddress)
            val port = proxy.port
            proxy.start()
            assertEquals(port, proxy.port)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `server EOF closes a client that is still waiting for data`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(6000, 1, loopback).use { xServer ->
            xServer.soTimeout = 5000
            val proxy = X11Proxy { error("Expected the local test X server to be reachable") }
            try {
                proxy.start()
                Socket(loopback, proxy.port).use { client ->
                    client.soTimeout = 5000
                    xServer.accept().use { serverPeer ->
                        serverPeer.getOutputStream().write(42)
                        assertEquals(42, client.getInputStream().read())
                    }
                    assertEquals(-1, client.getInputStream().read())
                }
            } finally {
                proxy.stop()
            }
        }
    }
}
