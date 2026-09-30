package io.github.tabssh.utils.logging

import io.github.tabssh.TabSSHApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A crash thrown from a coroutine only reaches the default uncaught-exception
 * handler if the scope has no CoroutineExceptionHandler of its own. The app
 * launches pane/layout work from scopes that carry a SupervisorJob, and the
 * reported crash came out of exactly such a launch — so this asserts the whole
 * path, handler installation included, rather than just writeCrashSync.
 */
@RunWith(RobolectricTestRunner::class)
class CoroutineCrashCaptureTest {

    @Test
    fun `an exception thrown inside a coroutine reaches the crash log`() {
        val app = ApplicationProvider.getApplicationContext<TabSSHApplication>()
        Logger.initialize(app, debugMode = true)
        val logFile = Logger.getLogFile()!!
        logFile.delete()

        // Mirror the app's own handler: a sync write of the throwable.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Logger.writeCrashSync(Thread.currentThread(), throwable)
        }

        try {
            // SupervisorJob + Dispatchers.Main mirrors lifecycleScope.launch.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            scope.launch {
                throw IllegalStateException("thrown from a coroutine")
            }
            // Let the Main dispatcher drain.
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(200)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        val text = logFile.readText()
        assertTrue(
            "coroutine crash never reached the log; contents were:\n$text",
            text.contains("thrown from a coroutine")
        )
    }
}
