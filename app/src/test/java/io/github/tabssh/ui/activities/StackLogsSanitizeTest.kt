package io.github.tabssh.ui.activities

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the compose-log line sanitiser.
 *
 * Compose log content is fully remote-controlled. Before the fix only ANSI
 * escapes were stripped, so a hostile image could emit bidi overrides or C1
 * controls that reorder or spoof surrounding log text, or a single
 * multi-megabyte line that stalls the TextView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StackLogsSanitizeTest {

    @Test
    fun `ansi colour escapes are stripped`() {
        assertEquals(
            "hello",
            StackLogsActivity.sanitizeLogLine("\u001B[31mhello\u001B[0m")
        )
    }

    @Test
    fun `osc sequences are stripped`() {
        assertEquals(
            "ok",
            StackLogsActivity.sanitizeLogLine("\u001B]0;title\u0007ok")
        )
    }

    @Test
    fun `bidi overrides are stripped`() {
        assertEquals(
            "abc",
            StackLogsActivity.sanitizeLogLine("a\u202Eb\u2066c\u2069")
        )
    }

    @Test
    fun `c1 controls are stripped`() {
        assertEquals("ab", StackLogsActivity.sanitizeLogLine("a\u0085\u009Bb"))
    }

    @Test
    fun `tabs survive because log columns depend on them`() {
        assertEquals("a\tb", StackLogsActivity.sanitizeLogLine("a\tb"))
    }

    @Test
    fun `over long lines are capped`() {
        val line = StackLogsActivity.sanitizeLogLine("x".repeat(9000))
        assertEquals(4096, line.length - 1, "expected cap plus ellipsis")
        assertTrue(line.endsWith("…"))
    }
}
