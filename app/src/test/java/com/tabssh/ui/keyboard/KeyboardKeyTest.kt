package io.github.tabssh.ui.keyboard

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the custom keyboard key palette — specifically the escape
 * sequences the navigation keys emit.
 *
 * HOME/END regression guard: the xterm CSI-letter forms (\e[H / \e[F)
 * are unbound on TERM=screen*-family/tmux*-family sessions and on any host whose
 * inputrc lacks the binding — readline then swallows the ESC[ prefix
 * and inserts a literal "H"/"F". The VT220 tilde forms (\e[1~ / \e[4~)
 * are bound by every distro's default inputrc and by screen/tmux
 * terminfo, so they are the sequences the palette must emit.
 */
class KeyboardKeyTest {

    private val keys = KeyboardKey.getAllAvailableKeys().associateBy { it.id }

    private fun seq(id: String): String =
        keys.getValue(id).keySequence

    @Test
    fun `HOME emits VT220 tilde form`() {
        assertEquals("\u001b[1~", seq("HOME"))
    }

    @Test
    fun `END emits VT220 tilde form`() {
        assertEquals("\u001b[4~", seq("END"))
    }

    @Test
    fun `PGUP and PGDN emit VT220 tilde forms`() {
        assertEquals("\u001b[5~", seq("PGUP"))
        assertEquals("\u001b[6~", seq("PGDN"))
    }

    @Test
    fun `INSERT and DELETE emit VT220 tilde forms`() {
        assertEquals("\u001b[2~", seq("INSERT"))
        assertEquals("\u001b[3~", seq("DELETE"))
    }

    @Test
    fun `arrow keys emit normal-mode CSI sequences`() {
        assertEquals("\u001b[A", seq("UP"))
        assertEquals("\u001b[B", seq("DOWN"))
        assertEquals("\u001b[C", seq("RIGHT"))
        assertEquals("\u001b[D", seq("LEFT"))
    }

    @Test
    fun `F1-F4 emit SS3 and F5-F12 emit tilde forms`() {
        assertEquals("\u001bOP", seq("F1"))
        assertEquals("\u001bOQ", seq("F2"))
        assertEquals("\u001bOR", seq("F3"))
        assertEquals("\u001bOS", seq("F4"))
        assertEquals("\u001b[15~", seq("F5"))
        assertEquals("\u001b[17~", seq("F6"))
        assertEquals("\u001b[18~", seq("F7"))
        assertEquals("\u001b[19~", seq("F8"))
        assertEquals("\u001b[20~", seq("F9"))
        assertEquals("\u001b[21~", seq("F10"))
        assertEquals("\u001b[23~", seq("F11"))
        assertEquals("\u001b[24~", seq("F12"))
    }

    @Test
    fun `no key emits the fragile CSI-letter Home or End form`() {
        KeyboardKey.getAllAvailableKeys().forEach { key ->
            assertTrue(
                key.keySequence != "\u001b[H" && key.keySequence != "\u001b[F",
                "${key.id} emits the unbound-on-screen/tmux CSI form"
            )
        }
    }
}
