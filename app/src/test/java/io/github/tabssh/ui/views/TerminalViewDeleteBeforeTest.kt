package io.github.tabssh.ui.views

import android.app.Application
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounds and composing-awareness tests for
 * TerminalInputConnection.deleteSurroundingText().
 *
 * An IME (or a malformed one) can ask for an arbitrarily large deletion. The
 * old implementation forwarded one DEL byte per requested character with no
 * upper bound and no knowledge of the buffered composition, so a backspace
 * during composition both deleted a character the remote had never seen and
 * could turn a single call into an unbounded burst on the wire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalViewDeleteBeforeTest {

    private fun newConnectedView(): Pair<TerminalView, ByteArrayOutputStream> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = TerminalView(context)
        view.initialize(24, 80)

        val emulatorField = TerminalView::class.java.getDeclaredField("terminalEmulator")
        emulatorField.isAccessible = true
        val emulator = emulatorField.get(view) as io.github.tabssh.terminal.emulator.TerminalEmulator

        // Attach only the output stream, for the same reason as
        // TerminalViewComposingFlushTest: connect() would start a read loop
        // that hits EOF and closes the stream out from under the assertions.
        val out = ByteArrayOutputStream()
        emulator.attachOutputStream(out)
        return Pair(view, out)
    }

    private fun createInputConnection(view: TerminalView): android.view.inputmethod.InputConnection {
        val editorInfo = EditorInfo()
        return view.onCreateInputConnection(editorInfo)
    }

    @Test
    fun `deletion consumes composing text before reaching the wire`() {
        val (view, out) = newConnectedView()
        val ic = createInputConnection(view)

        ic.setComposingText("abc", 1)
        ic.deleteSurroundingText(2, 0)
        ic.finishComposingText()

        assertEquals("a", out.toString("UTF-8"))
    }

    @Test
    fun `deletion past the composition sends DEL for the remainder only`() {
        val (view, out) = newConnectedView()
        val ic = createInputConnection(view)

        ic.setComposingText("ab", 1)
        ic.deleteSurroundingText(5, 0)

        assertEquals("\u007F\u007F\u007F", out.toString("UTF-8"))
    }

    @Test
    fun `an oversized deletion request is bounded`() {
        val (view, out) = newConnectedView()
        val ic = createInputConnection(view)

        ic.deleteSurroundingText(Int.MAX_VALUE, 0)

        val sent = out.toString("UTF-8")
        assertEquals(1024, sent.length)
        assertTrue(sent.all { it == '\u007F' })
    }

    @Test
    fun `a negative or zero deletion request sends nothing`() {
        val (view, out) = newConnectedView()
        val ic = createInputConnection(view)

        ic.deleteSurroundingText(0, 0)
        ic.deleteSurroundingText(-5, 3)

        assertEquals("", out.toString("UTF-8"))
    }

    @Test
    fun `code point deletion is bounded the same way`() {
        val (view, out) = newConnectedView()
        val ic = createInputConnection(view)

        ic.deleteSurroundingTextInCodePoints(Int.MAX_VALUE, 0)

        assertEquals(1024, out.toString("UTF-8").length)
    }
}
