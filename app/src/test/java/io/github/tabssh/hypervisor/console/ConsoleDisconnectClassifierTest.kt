package io.github.tabssh.hypervisor.console

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the RFB session-end decision table behind the VNC/SPICE tab close
 * policy: only an EOF at a protocol message boundary is a CLEAN (orderly)
 * server shutdown; everything else — mid-message EOF, socket reset, generic
 * I/O, TLS, or protocol errors — is an ERROR that must offer reconnect.
 */
class ConsoleDisconnectClassifierTest {

    @Test
    fun `EOF at message boundary is clean`() {
        assertEquals(
            ConsoleDisconnectReason.CLEAN,
            ConsoleDisconnectClassifier.classifyRfb(atMessageBoundary = true, e = EOFException())
        )
    }

    @Test
    fun `EOF mid-message is an error`() {
        assertEquals(
            ConsoleDisconnectReason.ERROR,
            ConsoleDisconnectClassifier.classifyRfb(atMessageBoundary = false, e = EOFException())
        )
    }

    @Test
    fun `socket reset is an error even at a boundary`() {
        assertEquals(
            ConsoleDisconnectReason.ERROR,
            ConsoleDisconnectClassifier.classifyRfb(
                atMessageBoundary = true, e = SocketException("Connection reset"))
        )
    }

    @Test
    fun `generic IOException is an error`() {
        assertEquals(
            ConsoleDisconnectReason.ERROR,
            ConsoleDisconnectClassifier.classifyRfb(
                atMessageBoundary = true, e = IOException("read failed"))
        )
    }

    @Test
    fun `TLS failure is an error`() {
        assertEquals(
            ConsoleDisconnectReason.ERROR,
            ConsoleDisconnectClassifier.classifyRfb(
                atMessageBoundary = false, e = SSLException("handshake aborted"))
        )
    }

    @Test
    fun `protocol desync exception is an error`() {
        assertEquals(
            ConsoleDisconnectReason.ERROR,
            ConsoleDisconnectClassifier.classifyRfb(
                atMessageBoundary = false, e = IllegalStateException("unknown message type 0xB9"))
        )
    }
}
