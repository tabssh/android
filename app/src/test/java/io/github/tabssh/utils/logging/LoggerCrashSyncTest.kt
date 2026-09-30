package io.github.tabssh.utils.logging

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [Logger.writeCrashSync] is the only thing standing between an uncaught
 * exception and no evidence at all, and it runs moments before the process
 * dies. If it silently fails, the log the user exports to report a crash
 * contains no trace of that crash.
 */
@RunWith(RobolectricTestRunner::class)
class LoggerCrashSyncTest {

    @Test
    fun `writeCrashSync persists a crash block to the debug log`() {
        Logger.initialize(ApplicationProvider.getApplicationContext(), debugMode = true)
        val logFile = Logger.getLogFile()!!
        logFile.delete()

        Logger.writeCrashSync(Thread.currentThread(), IllegalStateException("boom from the test"))

        val text = logFile.readText()
        assertTrue("crash block missing from debug log", text.contains("UNCAUGHT EXCEPTION"))
        assertTrue("exception type missing", text.contains("IllegalStateException"))
        assertTrue("message missing", text.contains("boom from the test"))
        assertTrue("stack trace missing", text.contains("writeCrashSync"))
    }

    @Test
    fun `writeCrashSync writes to the app log even when debug mode is off`() {
        Logger.initialize(ApplicationProvider.getApplicationContext(), debugMode = false)
        val appLog = Logger.getAppLogFile()!!
        appLog.delete()

        Logger.writeCrashSync(Thread.currentThread(), IllegalStateException("release-build crash"))

        val text = appLog.readText()
        assertTrue("app log should capture the crash regardless of debug mode", text.contains("APP CRASHED"))
        assertTrue(text.contains("release-build crash"))
    }
}
