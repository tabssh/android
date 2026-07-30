package io.github.tabssh.ui.tabs

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for SSHTab.buildMultiplexerCommand — the post-connect command
 * assembled for the multiplexer auto-launch feature.
 *
 * The tmux forms must chain a session-scoped `set -q mouse on`
 * (escaped `\;` so the remote shell hands tmux a literal `;`):
 * mouse mode makes tmux enable client mouse tracking, so swipe
 * gestures are forwarded as wheel events and tmux scrolls its
 * server-side scrollback — swipe-to-scroll acts like a scrollbar.
 */
class SSHTabMultiplexerCommandTest {

    private fun cmd(type: String, mode: String, name: String = "tabssh"): String? =
        SSHTab.buildMultiplexerCommand(type, mode, name)

    @Test
    fun `tmux attach modes enable session-scoped mouse mode`() {
        val expected = "tmux new -A -s 'tabssh' \\; set -q mouse on"
        assertEquals(expected, cmd("tmux", "AUTO_ATTACH"))
        assertEquals(expected, cmd("tmux", "ASK"))
    }

    @Test
    fun `tmux create-new enables session-scoped mouse mode`() {
        assertEquals("tmux new -s 'tabssh' \\; set -q mouse on", cmd("tmux", "CREATE_NEW"))
    }

    @Test
    fun `screen commands are unchanged - no mouse support exists`() {
        assertEquals("screen -RR 'tabssh'", cmd("screen", "AUTO_ATTACH"))
        assertEquals("screen -RR 'tabssh'", cmd("screen", "ASK"))
        assertEquals("screen -S 'tabssh'", cmd("screen", "CREATE_NEW"))
    }

    @Test
    fun `zellij commands are unchanged - mouse mode is its default`() {
        assertEquals("zellij attach --create 'tabssh'", cmd("zellij", "AUTO_ATTACH"))
        assertEquals("zellij attach --create 'tabssh'", cmd("zellij", "ASK"))
        assertEquals("zellij --session 'tabssh'", cmd("zellij", "CREATE_NEW"))
    }

    @Test
    fun `session name is stripped of single quotes`() {
        assertEquals(
            "tmux new -A -s 'devbox' \\; set -q mouse on",
            cmd("tmux", "AUTO_ATTACH", "dev'box")
        )
    }

    @Test
    fun `unknown type or mode returns null`() {
        assertNull(cmd("byobu", "AUTO_ATTACH"))
        assertNull(cmd("tmux", "OFF"))
        assertNull(cmd("screen", "OFF"))
        assertNull(cmd("zellij", "OFF"))
    }
}
