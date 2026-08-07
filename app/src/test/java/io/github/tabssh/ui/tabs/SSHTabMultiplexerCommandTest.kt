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
    fun `single quotes in a session name are escaped, not dropped`() {
        assertEquals(
            "tmux new -A -s 'dev'\\''box' \\; set -q mouse on",
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

    @Test
    fun `attach commands target the chosen session`() {
        assertEquals(
            "tmux attach -t 'work' \\; set -q mouse on",
            SSHTab.buildAttachCommand("tmux", "work")
        )
        assertEquals("screen -r '1234.work'", SSHTab.buildAttachCommand("screen", "1234.work"))
        assertEquals("zellij attach 'work'", SSHTab.buildAttachCommand("zellij", "work"))
        assertNull(SSHTab.buildAttachCommand("byobu", "work"))
    }

    @Test
    fun `attach escapes single quotes in the session name`() {
        assertEquals(
            "tmux attach -t 'dev'\\''box' \\; set -q mouse on",
            SSHTab.buildAttachCommand("tmux", "dev'box")
        )
    }

    /**
     * Session names come from the remote host's own listing output, so a
     * compromised or hostile server controls this string. Single-quoting must
     * leave every metacharacter inert inside one argument.
     */
    @Test
    fun `shell metacharacters in a remote session name stay inert`() {
        assertEquals(
            "screen -r 'x'\\''; rm -rf ~; '\\'''",
            SSHTab.buildAttachCommand("screen", "x'; rm -rf ~; '")
        )
        assertEquals(
            "zellij attach '\$(id)`id`|cat;\nfoo'",
            SSHTab.buildAttachCommand("zellij", "\$(id)`id`|cat;\nfoo")
        )
    }

    @Test
    fun `tmux session list is one bare name per line`() {
        assertEquals(
            listOf("main", "work"),
            SSHTab.parseMultiplexerSessions("tmux", "main\nwork\n")
        )
        assertEquals(emptyList(), SSHTab.parseMultiplexerSessions("tmux", "\n"))
    }

    @Test
    fun `screen session list extracts pid-dot-name tokens`() {
        val raw = """
            There are screens on:
                1234.work	(Detached)
                5678.play	(Attached)
            2 Sockets in /run/screen/S-user.
        """.trimIndent()
        assertEquals(
            listOf("1234.work", "5678.play"),
            SSHTab.parseMultiplexerSessions("screen", raw)
        )
    }

    @Test
    fun `zellij session list strips ansi and skips boilerplate`() {
        val esc = "\u001B"
        val raw = "${esc}[32;1mmain${esc}[m [Created 2h ago]\nwork [Created 1m ago]\n"
        assertEquals(
            listOf("main", "work"),
            SSHTab.parseMultiplexerSessions("zellij", raw)
        )
        assertEquals(
            emptyList(),
            SSHTab.parseMultiplexerSessions("zellij", "No active zellij sessions found.\n")
        )
    }
}
