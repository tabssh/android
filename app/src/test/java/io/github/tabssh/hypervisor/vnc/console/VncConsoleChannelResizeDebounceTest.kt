package io.github.tabssh.hypervisor.vnc.console

import io.github.tabssh.hypervisor.console.rfb.RfbClient
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import kotlin.test.assertEquals

/**
 * Regression coverage for the Item 11 VNC keyboard-toggle padding fix:
 * [VncConsoleChannel.resizeToPixels] must debounce the actual
 * [RfbClient.sendSetDesktopSize] send so a rapid burst of size changes
 * (soft-keyboard show/hide firing several [android.view.View.onSizeChanged]
 * calls in quick succession) settles on exactly one request with the final
 * size, instead of one SetDesktopSize per intermediate size.
 *
 * Uses a [FakeResizeDebouncer] instead of real elapsed time: each call to
 * [ResizeDebouncer.schedule] simulates cancelling the previous pending action
 * and replacing it with the new one, exactly like the real
 * [ScheduledExecutorResizeDebouncer] does — only the last action survives
 * until it is manually fired.
 */
class VncConsoleChannelResizeDebounceTest {

    private class FakeResizeDebouncer : ResizeDebouncer {
        var pendingAction: (() -> Unit)? = null
        var scheduleCallCount = 0
        var shutdownCallCount = 0

        override fun schedule(action: () -> Unit) {
            scheduleCallCount++
            pendingAction = action
        }

        override fun shutdown() {
            shutdownCallCount++
            pendingAction = null
        }

        fun fire() {
            pendingAction?.invoke()
        }
    }

    @Test
    fun `two size events debounce to a single SetDesktopSize with the final size`() {
        val rfbClient = mock(RfbClient::class.java)
        val debouncer = FakeResizeDebouncer()
        val channel = VncConsoleChannel(rfbClient, debouncer)

        // Simulates two onSizeChanged callbacks 30ms apart during a keyboard
        // show/hide animation — well inside the ~80ms debounce window.
        channel.resizeToPixels(1080, 1920)
        channel.resizeToPixels(1080, 1740)

        // Each call must have (re)scheduled, cancelling the previous pending
        // send rather than letting both through.
        assertEquals(2, debouncer.scheduleCallCount)

        debouncer.fire()

        verify(rfbClient, timeout(1000)).sendSetDesktopSize(1080, 1740)
        verifyNoMoreInteractions(rfbClient)
    }

    @Test
    fun `close shuts down the debouncer`() {
        val rfbClient = mock(RfbClient::class.java)
        val debouncer = FakeResizeDebouncer()
        val channel = VncConsoleChannel(rfbClient, debouncer)

        channel.close()

        assertEquals(1, debouncer.shutdownCallCount)
    }
}
