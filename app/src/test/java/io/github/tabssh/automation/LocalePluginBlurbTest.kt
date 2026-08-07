package io.github.tabssh.automation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for LocalePlugin.buildBlurb — the summary line shown in the
 * automation host's task editor. Pure string work, so no Android runtime
 * is needed here; the bundle build/validate paths are covered under
 * Robolectric in LocalePluginBundleTest.
 */
class LocalePluginBlurbTest {

    @Test
    fun `blurb names the verb and connection`() {
        assertEquals(
            "Connect → devbox",
            LocalePlugin.buildBlurb(TaskerWorker.ACTION_CONNECT, "devbox", null, null)
        )
        assertEquals(
            "Disconnect → devbox",
            LocalePlugin.buildBlurb(TaskerWorker.ACTION_DISCONNECT, "devbox", null, null)
        )
        assertEquals(
            "Run: uptime → devbox",
            LocalePlugin.buildBlurb(TaskerWorker.ACTION_SEND_COMMAND, "devbox", "uptime", null)
        )
        assertEquals(
            "Keys: CTRL+C → devbox",
            LocalePlugin.buildBlurb(TaskerWorker.ACTION_SEND_KEYS, "devbox", null, "CTRL+C")
        )
    }

    @Test
    fun `blurb is capped at 60 chars`() {
        val blurb = LocalePlugin.buildBlurb(
            TaskerWorker.ACTION_SEND_COMMAND, "devbox", "x".repeat(200), null
        )
        assertTrue(blurb.length <= 60)
    }
}
