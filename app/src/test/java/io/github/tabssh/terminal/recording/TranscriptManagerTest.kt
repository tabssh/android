package io.github.tabssh.terminal.recording

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounds tests for transcript reading. A recorded session can reach the 32 MB
 * on-disk cap, and the viewer used to call readText() on whatever it found.
 */
class TranscriptManagerTest {

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
        assertEquals("hello transcript", TranscriptManager.getTranscriptContent(transcriptOf(f)))
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

        val content = TranscriptManager.getTranscriptContent(transcriptOf(f))
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
            TranscriptManager.getTranscriptContent(transcriptOf(missing))
        )
    }

    @Test
    fun `file size formatting matches the viewer labels`() {
        assertEquals("512 B", TranscriptManager.formatFileSize(512))
        assertEquals("2 KB", TranscriptManager.formatFileSize(2048))
        assertEquals("3 MB", TranscriptManager.formatFileSize(3L * 1024 * 1024))
    }
}
