package io.github.tabssh.ui.views

import android.app.Application
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Regression test for Issue: page up/down (and other bar/hardware-key
 * escape writes) corrupting an in-flight IME composition — e.g.
 * `{command} ....` losing its trailing space to become `{command}....`.
 *
 * Root cause: TerminalInputConnection buffered setComposingText() without
 * forwarding it, onCreateInputConnection() handed back a brand-new instance
 * on every reattach (dropping the buffer), and closeConnection() was a
 * no-op — so composing text could be silently discarded instead of ever
 * reaching the terminal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalViewComposingFlushTest {

    private fun newConnectedView(): Pair<TerminalView, ByteArrayOutputStream> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = TerminalView(context)
        view.initialize(24, 80)

        val emulatorField = TerminalView::class.java.getDeclaredField("terminalEmulator")
        emulatorField.isAccessible = true
        val emulator = emulatorField.get(view) as io.github.tabssh.terminal.emulator.TerminalEmulator

        val out = ByteArrayOutputStream()
        emulator.connect(ByteArrayInputStream(ByteArray(0)), out)
        return Pair(view, out)
    }

    private fun createInputConnection(view: TerminalView): android.view.inputmethod.InputConnection {
        val editorInfo = EditorInfo()
        return view.onCreateInputConnection(editorInfo)
    }

    @Test
    fun `closeConnection flushes pending composing text exactly once`() {
        val (view, out) = newConnectedView()
        val ic = createInputConnection(view)

        ic.setComposingText("foo", 1)
        ic.closeConnection()

        assertEquals("foo", out.toString("UTF-8"))

        // A second close (or any further flush) must not resend it.
        ic.closeConnection()
        assertEquals("foo", out.toString("UTF-8"))
    }

    @Test
    fun `composing text survives onCreateInputConnection replacement`() {
        val (view, out) = newConnectedView()
        val ic1 = createInputConnection(view)

        ic1.setComposingText("foo", 1)

        // Simulate the system recreating the InputConnection on a
        // focus/config change before the IME finalised the composition.
        val ic2 = createInputConnection(view)
        ic2.closeConnection()

        assertEquals("foo", out.toString("UTF-8"))
    }

    @Test
    fun `flushPendingComposing sends composing text before an escape write races ahead`() {
        val (view, out) = newConnectedView()
        val ic = createInputConnection(view)

        ic.setComposingText("git -C ", 1)
        // Mirrors TerminalView.onKeyDown's PGUP/PGDN handling and
        // TabTerminalActivity.handleCustomKeyPress flushing before any
        // bar/hardware-key escape sequence is written directly.
        view.flushPendingComposing()
        view.sendKeySequence("[5~")

        assertEquals("git -C [5~", out.toString("UTF-8"))
    }
}
