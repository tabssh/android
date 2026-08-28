package io.github.tabssh.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.tabssh.utils.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * MediaStore-scoped save location for the session video recorder
 * (TODO.AI.md item 53): both the mp4 and its paired `.cast` file land in the
 * same user-visible `Movies/TabSSH` folder, matching the TODO item's "saved
 * to the device's Videos/TabSSH directory" requirement.
 *
 * No prior MediaStore convention exists elsewhere in the app — every other
 * "save a file" path (e.g. [io.github.tabssh.terminal.recording.SessionRecorder])
 * uses app-private [Context.getExternalFilesDir], which is invisible outside
 * the app. This is deliberately different: recordings are meant to show up in
 * Photos/Gallery/Files like any other user-captured video.
 *
 * On API 29+ (scoped storage), files are inserted through [android.content.ContentResolver]
 * with `IS_PENDING` set while being written, cleared once the recorder finishes — the
 * standard MediaStore write pattern. Below API 29, we fall back to a direct
 * legacy path under the public Movies directory and a manual media-scanner nudge.
 */
object VideoRecordingStorage {

    private const val TAG = "VideoRecordingStorage"
    private const val RELATIVE_PATH = "Movies/TabSSH"

    private data class PendingEntry(val uri: Uri?, val legacyFile: File?)

    // Keyed by display filename — both AsciinemaCastWriter and
    // SessionRecordingService finalize by filename rather than holding onto
    // a Uri/File handle themselves, keeping their call sites to one line.
    private val pending = ConcurrentHashMap<String, PendingEntry>()

    /**
     * Open (creating if needed) a pending mp4 target for [filename] and return
     * a writable [ParcelFileDescriptor] for [android.media.MediaRecorder.setOutputFile].
     * Returns null if the target could not be opened.
     */
    fun openPendingVideoFd(context: Context, filename: String): ParcelFileDescriptor? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                pending[filename] = PendingEntry(uri, null)
                context.contentResolver.openFileDescriptor(uri, "w")
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "TabSSH"
                )
                dir.mkdirs()
                val file = File(dir, filename)
                pending[filename] = PendingEntry(null, file)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to open pending video target for $filename", e)
            null
        }
    }

    /**
     * Open (creating if needed) a pending `.cast` (JSON) output stream for
     * [filename], saved alongside the mp4 in the same [RELATIVE_PATH]. Goes
     * through the generic `MediaStore.Files` collection since `.cast` is not
     * a video/audio/image type MediaStore.Video would accept.
     */
    fun openCastOutputStream(context: Context, filename: String): OutputStream? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, filename)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
                val collection = MediaStore.Files.getContentUri("external")
                val uri = context.contentResolver.insert(collection, values) ?: return null
                pending[filename] = PendingEntry(uri, null)
                context.contentResolver.openOutputStream(uri)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "TabSSH"
                )
                dir.mkdirs()
                val file = File(dir, filename)
                pending[filename] = PendingEntry(null, file)
                FileOutputStream(file)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to open cast output stream for $filename", e)
            null
        }
    }

    /**
     * Clear `IS_PENDING` (API 29+) or nudge the media scanner (legacy path)
     * for a file previously opened via [openPendingVideoFd] or
     * [openCastOutputStream]. Safe to call once per filename; a second call
     * for an already-finalized/unknown filename is a no-op.
     */
    fun finalizePendingFile(context: Context, filename: String) {
        val entry = pending.remove(filename) ?: return
        try {
            if (entry.uri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(entry.uri, values, null, null)
            } else if (entry.legacyFile != null) {
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(entry.legacyFile.absolutePath),
                    null,
                    null
                )
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to finalize $filename", e)
        }
    }

    /**
     * Resolve a shareable content Uri for a previously-finalized recording
     * file, for the "Share" action (AI.md: SAF/scoped storage, never a raw
     * `file://` Uri off the app's own storage). MediaStore-backed files
     * already return a `content://` Uri directly; the legacy pre-API-29 path
     * needs [FileProvider] the same way [io.github.tabssh.ui.activities.SFTPActivity.shareFile] does.
     */
    fun shareableUriFor(context: Context, filename: String, legacyFile: File?): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Re-query rather than trust `pending` (already cleared by finalize).
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val collections = listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Files.getContentUri("external"))
            for (collection in collections) {
                context.contentResolver.query(collection, projection, selection, arrayOf(filename), null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        return Uri.withAppendedPath(collection, id.toString())
                    }
                }
            }
            null
        } else if (legacyFile != null && legacyFile.exists()) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", legacyFile)
        } else {
            null
        }
    }
}
