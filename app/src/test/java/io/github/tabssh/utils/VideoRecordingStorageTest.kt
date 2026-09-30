package io.github.tabssh.utils

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.FileNotFoundException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class VideoRecordingStorageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: RecordingProvider

    @Before
    fun setUp() {
        provider = RecordingProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test
    fun `failed video opens remove their pending rows`() {
        assertNull(VideoRecordingStorage.openPendingVideoFd(context, "failed.mp4"))
        assertEquals(1, provider.deleted.size)
        VideoRecordingStorage.finalizePendingFile(context, "failed.mp4")
        assertEquals(0, provider.updates)
    }

    @Test
    fun `sharing and listing select only finished recordings in the exact directory`() {
        VideoRecordingStorage.shareableUriFor(context, "name.mp4", null)
        assertEquals(listOf("name.mp4", "Movies/TabSSH/", "Documents/TabSSH/"), provider.args)
        assertTrue(provider.selection.orEmpty().contains("${MediaStore.MediaColumns.IS_PENDING} = 0"))
        VideoRecordingStorage.listRecordings(context)
        assertEquals(listOf("Movies/TabSSH/", "Documents/TabSSH/"), provider.args)
        assertTrue(provider.selection.orEmpty().contains("${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)"))
    }

    class RecordingProvider : ContentProvider() {
        val deleted = mutableListOf<Uri>()
        var updates = 0
        var selection: String? = null
        var args: List<String>? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?) = Uri.withAppendedPath(uri, "1")
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw FileNotFoundException("no space")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted.add(uri)
            return 1
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updates++
            return 1
        }
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?
        ): Cursor {
            this.selection = selection
            args = selectionArgs?.toList()
            return MatrixCursor(projection ?: arrayOf(MediaStore.MediaColumns._ID))
        }
    }
}
