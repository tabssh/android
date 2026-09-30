package io.github.tabssh.automation

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TaskerInputDataTest {
    @Test
    fun `unicode request below character limit is rejected if serialized data exceeds limit`() {
        val command = "界".repeat(5000)
        assertNull(TaskerInputData.create(TaskerWorker.ACTION_SEND_COMMAND, "id", "name", command, null, false))
        val bundle = LocalePlugin.buildBundle(TaskerWorker.ACTION_SEND_COMMAND, "id", "name", command, null, false)
        assertFalse(LocalePlugin.isBundleValid(bundle))
    }

    @Test
    fun `oversized executable text is rejected instead of truncated`() {
        assertNull(TaskerInputData.create(
            TaskerWorker.ACTION_SEND_COMMAND, "id", null,
            "x".repeat(LocalePlugin.MAX_COMMAND_LENGTH + 1), null, false
        ))
    }

    @Test
    fun `accepted commands retain all text and optional timeout`() {
        val command = "printf '你好' && echo done"
        val data = assertNotNull(TaskerInputData.create(
            TaskerWorker.ACTION_SEND_COMMAND, "id", "name", command, null, true, 1234
        ))
        assertEquals(command, data.getString(TaskerWorker.KEY_COMMAND))
        assertEquals(1234L, data.getLong(TaskerWorker.KEY_TIMEOUT_MS, 0))
    }
}
