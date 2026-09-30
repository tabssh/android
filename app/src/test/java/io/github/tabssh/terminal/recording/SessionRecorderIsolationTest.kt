package io.github.tabssh.terminal.recording

import android.app.Application
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionRecorderIsolationTest {
    @Test
    fun `simultaneous sessions with identical long names retain separate transcripts`() {
        val context = RuntimeEnvironment.getApplication()
        val title = "same-host".repeat(80)
        val first = SessionRecorder(context, title)
        val second = SessionRecorder(context, title)
        try {
            first.startRecording()
            second.startRecording()
            assertTrue(first.isRecording())
            assertTrue(second.isRecording())
            assertNotEquals(first.getCurrentFilePath(), second.getCurrentFilePath())
            first.recordOutput("ONLY_FIRST")
            second.recordOutput("ONLY_SECOND")
        } finally {
            first.stopRecording()
            second.stopRecording()
        }
        val firstText = File(requireNotNull(first.getCurrentFilePath())).readText()
        val secondText = File(requireNotNull(second.getCurrentFilePath())).readText()
        assertTrue(firstText.contains("ONLY_FIRST"))
        assertFalse(firstText.contains("ONLY_SECOND"))
        assertTrue(secondText.contains("ONLY_SECOND"))
        assertFalse(secondText.contains("ONLY_FIRST"))
    }
}
