package io.github.tabssh.utils.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogUploadFitTest {

    private fun log(lines: Int): String = buildString {
        appendLine("=== TabSSH Debug Log ===")
        appendLine("Exported: now")
        appendLine("========================")
        appendLine()
        for (i in 1..lines) appendLine("line-%05d some log text that takes room".format(i))
    }

    @Test
    fun `a log under the limit is returned unchanged`() {
        val text = log(10)
        assertEquals(text, LogUploadFit.fit(text, 100_000))
    }

    @Test
    fun `an oversized log keeps the header and the newest lines`() {
        val text = log(5000)
        val fitted = LogUploadFit.fit(text, 20_000)
        assertTrue(fitted.toByteArray().size <= 20_000)
        assertTrue(fitted.startsWith("=== TabSSH Debug Log ==="))
        assertTrue(fitted.contains("line-05000"))
        assertFalse(fitted.contains("line-00001"))
        assertTrue(fitted.contains("older lines omitted"))
    }

    @Test
    fun `rotated logs are dropped before current lines are cut`() {
        val text = log(200) + "=== Rotated log .1 ===\n" + "y".repeat(20_000)
        val fitted = LogUploadFit.fit(text, 18_000)
        assertFalse(fitted.contains("yyyy"))
        assertTrue(fitted.contains("line-00001"))
        assertTrue(fitted.contains("line-00200"))
    }
}
