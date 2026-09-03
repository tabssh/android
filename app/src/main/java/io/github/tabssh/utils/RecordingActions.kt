package io.github.tabssh.utils

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.terminal.recording.AsciinemaUploader
import io.github.tabssh.utils.logging.Logger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared user-facing actions on finished recordings (mp4 / `.cast`), used by
 * both the terminal's post-stop share prompt ([io.github.tabssh.ui.activities.TabTerminalActivity])
 * and the Recordings browser ([io.github.tabssh.ui.activities.RecordingsActivity]) —
 * one implementation of the share/play/upload flows instead of two.
 */
object RecordingActions {

    private const val TAG = "RecordingActions"

    /**
     * Pre-API-29 recordings live at a fixed public path; MediaStore-backed
     * (API 29+) recordings resolve by filename instead and need no File.
     */
    fun legacyFileFor(filename: String): File? {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "TabSSH/$filename"
            )
        } else {
            null
        }
    }

    /**
     * OS share sheet for a finished recording, mirroring
     * [io.github.tabssh.ui.activities.SFTPActivity.shareFile]'s exact
     * `ACTION_SEND` + `FileProvider`/MediaStore-Uri pattern.
     */
    fun share(activity: AppCompatActivity, filename: String, mimeType: String) {
        val uri = VideoRecordingStorage.shareableUriFor(activity, filename, legacyFileFor(filename))
        if (uri == null) {
            Toast.makeText(activity, R.string.sftp_error_share, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, filename))
        } catch (e: Exception) {
            Logger.e(TAG, "Error sharing recording $filename", e)
            Toast.makeText(activity, R.string.sftp_error_share, Toast.LENGTH_SHORT).show()
        }
    }

    /** Open a finished mp4 in the system video player. */
    fun play(activity: AppCompatActivity, filename: String) {
        val uri = VideoRecordingStorage.shareableUriFor(activity, filename, legacyFileFor(filename))
        if (uri == null) {
            Toast.makeText(activity, R.string.sftp_error_share, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Logger.e(TAG, "Error playing recording $filename", e)
            Toast.makeText(activity, R.string.sftp_error_share, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Upload a finished `.cast` to the configured asciinema server
     * (`asciinema_server_url` in preferences_recording.xml — self-hostable,
     * defaults to [AsciinemaUploader.DEFAULT_SERVER_URL]). On success the
     * returned cast URL is copied to the clipboard, mirroring how the rest of
     * the app surfaces one-off generated values.
     */
    fun uploadCast(activity: AppCompatActivity, filename: String) {
        val serverUrl = (activity.application as TabSSHApplication).preferencesManager.getString(
            "asciinema_server_url",
            AsciinemaUploader.DEFAULT_SERVER_URL
        )
        Toast.makeText(activity, R.string.video_recording_upload_cast, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AsciinemaUploader.uploadByFilename(activity, filename, legacyFileFor(filename), serverUrl)
                }
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            result.onSuccess { url ->
                ClipboardHelper.copy(activity, "Asciinema URL", url, sensitive = false)
                Toast.makeText(activity, activity.getString(R.string.video_recording_upload_success, url), Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Logger.e(TAG, "Asciinema upload failed for $filename", e)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.video_recording_upload_failure, e.message ?: e.toString()),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
