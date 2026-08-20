package io.github.tabssh.terminal.recording

import android.app.Application
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounds tests for transcript reading. A recorded session can reach the 32 MB
 * on-disk cap, and the viewer used to call readText() on whatever it found.
 *
 * Runs under Robolectric because the truncation banner is built with
 * Format.size(), which resolves unit names from plural string resources.
 * A stock Application is forced so the real TabSSHApplication (which touches
 * AndroidKeyStore on teardown) is never instantiated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TranscriptManagerTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @get:Rule
    val temp = TemporaryFolder()

    private fun transcriptOf(file: java.io.File) = TranscriptManager.Transcript(
        file = file,
        name = file.nameWithoutExtension,
        size = file.length(),
        timestamp = file.lastModified()
    )

    @Test
    fun `small transcripts are returned verbatim`() {
        val f = temp.newFile("session_small.log")
        f.writeText("hello transcript")
        assertEquals("hello transcript", TranscriptManager.getTranscriptContent(context, transcriptOf(f)))
    }

    @Test
    fun `large transcripts are truncated to their tail`() {
        val f = temp.newFile("session_large.log")
        val line = "x".repeat(1023) + "\n"
        f.bufferedWriter().use { w ->
            repeat(2048) { w.write(line) }
            w.write("FINAL LINE\n")
        }
        assertTrue(f.length() > 2L * 1024 * 1024)

        val content = TranscriptManager.getTranscriptContent(context, transcriptOf(f))
        assertTrue(content.startsWith("[Truncated:"), "expected a truncation banner, got: ${content.take(40)}")
        assertTrue(content.contains("FINAL LINE"), "the tail of the file must survive truncation")
        // Banner plus at most the cap, never the whole multi-megabyte file.
        assertTrue(content.length < 1024 * 1024 + 256, "content was not bounded: ${content.length}")
    }

    @Test
    fun `unreadable transcripts do not throw`() {
        val missing = java.io.File(temp.root, "does_not_exist.log")
        assertEquals(
            "Error reading transcript",
            TranscriptManager.getTranscriptContent(context, transcriptOf(missing))
        )
    }

    @Test
    fun `the truncation banner reports sizes in human-readable units`() {
        val f = temp.newFile("session_banner.log")
        val line = "y".repeat(1023) + "\n"
        f.bufferedWriter().use { w -> repeat(2048) { w.write(line) } }

        val content = TranscriptManager.getTranscriptContent(context, transcriptOf(f))
        assertTrue(content.startsWith("[Truncated: showing the last 1 megabyte of "), "got: ${content.take(60)}")
    }
}
