package io.github.tabssh.docker.transport

import io.github.tabssh.storage.database.entities.DockerHost
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * [SocketRelay.authenticateClient] — the accept-path preamble check that
 * hardens the loopback relay against any other local app connecting to it
 * (IDEA.md "JSch direct-streamlocal decision" / AI.md PART 6). Exercised
 * against a real loopback socket pair (no fakes for [Socket] itself — it is
 * effectively final for our purposes) standing in for a client connection.
 */
class SocketRelayAuthTest {

    private fun newRelay(): SocketRelay =
        SocketRelay(DockerHost(id = 1, name = "test"), SshExecRunner { null })

    @Test
    fun `correct token authenticates`() {
        val relay = newRelay()
        val server = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        var result = false
        val serverThread = Thread {
            server.accept().use { accepted -> result = relay.authenticateClient(accepted) }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val client = Socket("127.0.0.1", server.localPort)
        client.getOutputStream().apply { write(relay.token); flush() }
        serverThread.join(10_000)
        server.close()
        client.close()

        assertTrue(result)
    }

    @Test
    fun `wrong token is rejected`() {
        val relay = newRelay()
        val server = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        var result = true
        val serverThread = Thread {
            server.accept().use { accepted -> result = relay.authenticateClient(accepted) }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val client = Socket("127.0.0.1", server.localPort)
        val wrongToken = ByteArray(relay.token.size).also { SecureRandom().nextBytes(it) }
        client.getOutputStream().apply { write(wrongToken); flush() }
        serverThread.join(10_000)
        server.close()
        client.close()

        assertFalse(result)
    }

    @Test
    fun `disconnect before full token is rejected`() {
        val relay = newRelay()
        val server = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        var result = true
        val serverThread = Thread {
            server.accept().use { accepted -> result = relay.authenticateClient(accepted) }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val client = Socket("127.0.0.1", server.localPort)
        client.getOutputStream().apply {
            // Short by one byte, then hang up — the accept side must see this
            // as EOF and drop, not block waiting for the rest.
            write(relay.token, 0, relay.token.size - 1)
            flush()
        }
        client.close()
        serverThread.join(10_000)
        server.close()

        assertFalse(result)
    }

    @Test
    fun `stalled client is dropped by the preamble read timeout`() {
        val relay = newRelay()
        val server = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        var result = true
        val serverThread = Thread {
            server.accept().use { accepted -> result = relay.authenticateClient(accepted) }
        }
        serverThread.isDaemon = true
        serverThread.start()

        // Connect but never write anything — the accept side must give up on
        // its own instead of blocking the relay coroutine forever.
        val client = Socket("127.0.0.1", server.localPort)
        val joined = serverThread.also { it.join(10_000) }
        server.close()
        client.close()

        assertTrue(joined.isAlive.not())
        assertFalse(result)
    }

    @Test
    fun `token length matches the documented minimum`() {
        val relay = newRelay()

        assertEquals(32, relay.token.size)
    }
}
