package io.github.tabssh.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.tabssh.utils.VideoRecordingStorage
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class RecordingStorageTest {
    @Test
    fun castIsHiddenUntilFinalizedThenCanBeReadAndDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "cast-storage-test-${UUID.randomUUID()}.cast"
        val content = "{\"version\":2,\"width\":80,\"height\":24}\n"
        try {
            val stream = VideoRecordingStorage.openCastOutputStream(context, name)
            assertNotNull("MediaStore should accept the recording target", stream)
            requireNotNull(stream).use { it.write(content.toByteArray()) }
            assertFalse(VideoRecordingStorage.listRecordings(context).any { it.filename == name })
            VideoRecordingStorage.finalizePendingFile(context, name)
            assertTrue(VideoRecordingStorage.listRecordings(context).any { it.filename == name })
            val uri = requireNotNull(VideoRecordingStorage.shareableUriFor(context, name, null))
            val saved = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            assertEquals(content, saved)
            assertTrue(VideoRecordingStorage.deleteRecording(context, name, null))
        } finally {
            VideoRecordingStorage.discardPendingFile(context, name)
            VideoRecordingStorage.deleteRecording(context, name, null)
        }
    }
}
