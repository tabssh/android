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
 * Videos use `Movies/TabSSH`. On scoped storage, `.cast` documents use
 * `Documents/TabSSH` because MediaStore rejects non-media files in Movies.
 * Legacy devices keep both formats in Movies/TabSSH.
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
    private const val CAST_RELATIVE_PATH = "Documents/TabSSH"

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
                    ?: throw java.io.IOException("Unable to open pending video")
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
            discardPendingFile(context, filename)
            Logger.e(TAG, "Failed to open pending video target for $filename", e)
            null
        }
    }

    /**
     * Open (creating if needed) a pending `.cast` (JSON) output stream for
     * [filename], saved in Documents/TabSSH on scoped storage. Goes
     * through the generic `MediaStore.Files` collection since `.cast` is not
     * a video/audio/image type MediaStore.Video would accept.
     */
    fun openCastOutputStream(context: Context, filename: String): OutputStream? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, CAST_RELATIVE_PATH)
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, filename)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/x-asciicast")
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
                val collection = MediaStore.Files.getContentUri("external")
                val uri = context.contentResolver.insert(collection, values) ?: return null
                pending[filename] = PendingEntry(uri, null)
                context.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Unable to open pending cast")
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
            discardPendingFile(context, filename)
            Logger.e(TAG, "Failed to open cast output stream for $filename", e)
            null
        }
    }

    /** Remove a recording target when setup failed before it could be used. */
    fun discardPendingFile(context: Context, filename: String) {
        val entry = pending.remove(filename) ?: return
        try {
            if (entry.uri != null) {
                context.contentResolver.delete(entry.uri, null, null)
            } else {
                entry.legacyFile?.delete()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to discard unfinished recording $filename", e)
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
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
            val collections = listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Files.getContentUri("external"))
            for (collection in collections) {
                context.contentResolver.query(collection, projection, selection, arrayOf(filename, "$RELATIVE_PATH/", "$CAST_RELATIVE_PATH/"), null)?.use { cursor ->
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

    /** One saved recording file (mp4 or `.cast`) under [RELATIVE_PATH]. */
    data class Recording(
        val filename: String,
        val size: Long,
        val timestamp: Long,
        val isCast: Boolean,
    )

    /**
     * Enumerate finished videos and casts in their recording folders for the
     * Recordings browser. API 29+ queries MediaStore (videos from the Video
     * collection, `.cast` files from the generic Files collection); the
     * legacy path lists the public directory itself. Rows still marked
     * `IS_PENDING` (a recording in progress) are excluded by MediaStore's
     * default query behavior.
     */
    fun listRecordings(context: Context): List<Recording> {
        val results = mutableListOf<Recording>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                )
                // RELATIVE_PATH is stored with a trailing slash ("Movies/TabSSH/").
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
                val args = arrayOf("$RELATIVE_PATH/", "$CAST_RELATIVE_PATH/")
                val collections = listOf(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Files.getContentUri("external"),
                )
                for (collection in collections) {
                    context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIdx) ?: continue
                            val isCast = name.endsWith(".cast")
                            // The Files collection also indexes the mp4s the Video
                            // collection already returned — keep only `.cast` rows
                            // from it to avoid duplicates.
                            if (collection != MediaStore.Video.Media.EXTERNAL_CONTENT_URI && !isCast) continue
                            if (collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI && isCast) continue
                            results.add(
                                Recording(
                                    filename = name,
                                    size = cursor.getLong(sizeIdx),
                                    timestamp = cursor.getLong(dateIdx) * 1000L,
                                    isCast = isCast,
                                )
                            )
                        }
                    }
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "TabSSH"
                )
                dir.listFiles { f -> f.isFile }?.forEach { file ->
                    results.add(
                        Recording(
                            filename = file.name,
                            size = file.length(),
                            timestamp = file.lastModified(),
                            isCast = file.name.endsWith(".cast"),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to list recordings", e)
        }
        return results.sortedByDescending { it.timestamp }
    }

    /**
     * Delete a finished recording file. MediaStore rows this app inserted are
     * deletable without extra permissions; the legacy path deletes the file
     * directly and lets the media scanner drop the stale index entry.
     */
    fun deleteRecording(context: Context, filename: String, legacyFile: File?): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = shareableUriFor(context, filename, null) ?: return false
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                legacyFile != null && legacyFile.delete()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete recording $filename", e)
            false
        }
    }
}
