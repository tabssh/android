package io.github.tabssh.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellCommandEscaperTest {

    @Test
    fun `quotes ordinary and shell metacharacter values literally`() {
        assertEquals("'/home/user/My Documents'", ShellCommandEscaper.quotePosixArgument("/home/user/My Documents"))
        assertEquals("'$(touch /tmp/pwned);*'", ShellCommandEscaper.quotePosixArgument("$(touch /tmp/pwned);*"))
    }

    @Test
    fun `escapes embedded single quotes`() {
        assertEquals("'a'\\''b'", ShellCommandEscaper.quotePosixArgument("a'b"))
    }
}
