package io.github.tabssh.sftp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.utils.FileOpenPolicy
import io.github.tabssh.utils.Format
import io.github.tabssh.utils.NotificationHelper
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The file:// "Open" round trip shared by TabTerminalActivity (tapped
 * file:// terminal links) and SFTPActivity (remote file long-press menu):
 * stat for size → size gate → download into cacheDir/file-links/ via the
 * existing SFTPManager transfer queue → LRU eviction → ACTION_VIEW (with a
 * writable FileProvider grant, so viewers that support editing can save in
 * place) → on resume, detect a local change and prompt to upload it back.
 * Never uploads silently.
 *
 * Construct one instance per hosting activity, during onCreate (it adds
 * itself as a lifecycle observer to catch the resume after the external
 * viewer/editor returns).
 */
class RemoteFileOpener(
    private val activity: AppCompatActivity,
    private val sizeLimitMbProvider: () -> Int
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "RemoteFileOpener"
        const val CACHE_SUBDIR = "file-links"
    }

    private data class PendingEdit(
        val localFile: File,
        val remotePath: String,
        val sftpManager: SFTPManager,
        val originalMtime: Long,
        val originalSize: Long,
        // True when this opener dialed the SFTP channel itself and must
        // disconnect it once the round trip ends (TabTerminalActivity's
        // ad-hoc managers); false for a caller-owned long-lived manager.
        val ownsManager: Boolean
    )

    private var pendingEdit: PendingEdit? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        checkPendingEditForChanges()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // Close any ad-hoc channel still waiting on an upload-back decision —
        // leaked channels accumulate against the server's MaxSessions cap.
        clearPending()
    }

    // Disconnects [sftpManager] off the UI thread when this opener owns it.
    private fun releaseManager(sftpManager: SFTPManager, owns: Boolean) {
        if (!owns) return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try { sftpManager.disconnect() } catch (e: Exception) {
                Logger.w(TAG, "Ad-hoc SFTP disconnect failed: ${e.message}")
            }
        }
    }

    // Drops the tracked edit, releasing its channel if owned.
    private fun clearPending() {
        pendingEdit?.let { releaseManager(it.sftpManager, it.ownsManager) }
        pendingEdit = null
    }

    /**
     * Stats [remotePath] over [sftpManager], applies the size gate, downloads
     * it, then launches an external viewer with a writable URI grant. The
     * file is tracked for the upload-back prompt on resume.
     *
     * Pass [ownsManager] = true when the caller dialed [sftpManager] solely
     * for this open — the opener then disconnects it when the round trip
     * finishes (viewer launch failure, declined upload-back, completed
     * upload-back, or activity destruction).
     */
    fun open(sftpManager: SFTPManager, remotePath: String, displayName: String, ownsManager: Boolean = false) {
        activity.lifecycleScope.launch {
            val stat = withContext(Dispatchers.IO) { sftpManager.getRemoteFileAttributes(remotePath) }
            if (stat == null) {
                Toast.makeText(activity, R.string.fileopen_stat_failed, Toast.LENGTH_LONG).show()
                releaseManager(sftpManager, ownsManager)
                return@launch
            }
            val limitMb = sizeLimitMbProvider()
            if (FileOpenPolicy.exceedsSizeGate(stat.size, limitMb)) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.fileopen_large_title)
                    .setMessage(
                        activity.getString(
                            R.string.fileopen_large_message_fmt,
                            displayName,
                            Format.size(activity, stat.size),
                            limitMb
                        )
                    )
                    .setPositiveButton(R.string.download_file) { _, _ -> downloadAndOpen(sftpManager, remotePath, displayName, ownsManager) }
                    .setNegativeButton(R.string.cancel) { _, _ -> releaseManager(sftpManager, ownsManager) }
                    .setOnCancelListener { releaseManager(sftpManager, ownsManager) }
                    .show()
            } else {
                downloadAndOpen(sftpManager, remotePath, displayName, ownsManager)
            }
        }
    }

    private fun downloadAndOpen(sftpManager: SFTPManager, remotePath: String, displayName: String, ownsManager: Boolean) {
        val cacheSubDir = File(activity.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val localFile = File(cacheSubDir, FileOpenPolicy.cacheFileName(remotePath))

        activity.lifecycleScope.launch {
            try {
                val task = withContext(Dispatchers.IO) {
                    sftpManager.downloadFile(
                        remotePath = remotePath,
                        localFile = localFile,
                        listener = object : TransferListener {
                            override fun onProgress(transfer: TransferTask, bytesTransferred: Long, totalBytes: Long) {
                                NotificationHelper.showFileTransferProgress(
                                    activity, transfer.id.hashCode(), displayName, bytesTransferred, totalBytes, isUpload = false
                                )
                            }
                        }
                    )
                }
                val result = awaitTransfer(task)
                NotificationHelper.cancelNotification(activity, task.id.hashCode())
                if (result !is TransferResult.Success) {
                    val message = (result as? TransferResult.Error)?.message
                        ?: activity.getString(R.string.fileopen_download_failed)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.sftp_download_failed_fmt, message),
                        Toast.LENGTH_LONG
                    ).show()
                    releaseManager(sftpManager, ownsManager)
                    return@launch
                }
                withContext(Dispatchers.IO) { evictCache(cacheSubDir) }
                launchViewer(sftpManager, remotePath, localFile, ownsManager)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancellation is not a failure: leaving the screen mid-transfer
                // cancels this scope, and treating that as an error showed a
                // failure toast for a transfer the user simply walked away from.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Download failed for $remotePath", e)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.sftp_download_failed_fmt, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
                releaseManager(sftpManager, ownsManager)
            }
        }
    }

    /** Polls [task] to a terminal state — mirrors RemoteFileEditorActivity's transfer wait. */
    private suspend fun awaitTransfer(task: TransferTask): TransferResult {
        while (true) {
            when (task.state.value) {
                TransferState.COMPLETED, TransferState.ERROR, TransferState.CANCELLED ->
                    return task.result.value
                        ?: TransferResult.Error(activity.getString(R.string.fileopen_transfer_no_result))
                else -> delay(100)
            }
        }
    }

    private fun evictCache(dir: File) {
        val stats = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .map { FileOpenPolicy.CachedFileStat(it.name, it.lastModified(), it.length()) }
        val toEvict = FileOpenPolicy.filesToEvict(stats, FileOpenPolicy.DEFAULT_CACHE_CAP_BYTES)
        toEvict.forEach { File(dir, it.name).delete() }
    }

    private fun launchViewer(sftpManager: SFTPManager, remotePath: String, localFile: File, ownsManager: Boolean) {
        val uri: Uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", localFile)
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "FileProvider could not create a URI for ${localFile.name}", e)
            Toast.makeText(activity, R.string.fileopen_open_failed, Toast.LENGTH_LONG).show()
            releaseManager(sftpManager, ownsManager)
            return
        }
        val extension = FileOpenPolicy.extensionOf(localFile.name)
        val mimeType = extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        try {
            if (mimeType != null) {
                activity.startActivity(intent)
            } else {
                // Unknown type — force the resolver sheet instead of a single
                // app silently claiming "*/*".
                activity.startActivity(
                    Intent.createChooser(
                        intent,
                        activity.getString(R.string.fileopen_chooser_title_fmt, localFile.name)
                    )
                )
            }
            // Replacing an older tracked edit orphans its channel — close it
            // now if it was ours, or it would leak until activity destruction.
            clearPending()
            pendingEdit = PendingEdit(localFile, remotePath, sftpManager, localFile.lastModified(), localFile.length(), ownsManager)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.fileopen_no_app, Toast.LENGTH_LONG).show()
            releaseManager(sftpManager, ownsManager)
        }
    }

    private fun checkPendingEditForChanges() {
        val edit = pendingEdit ?: return
        if (!edit.localFile.exists()) {
            clearPending()
            return
        }
        val changed = FileOpenPolicy.hasFileChanged(
            edit.originalMtime, edit.originalSize,
            edit.localFile.lastModified(), edit.localFile.length()
        )
        if (!changed) return
        pendingEdit = null
        promptUploadBack(edit)
    }

    private fun promptUploadBack(edit: PendingEdit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.fileopen_changed_title)
            .setMessage(activity.getString(R.string.fileopen_upload_back_message_fmt, edit.remotePath))
            .setPositiveButton(R.string.upload_file) { _, _ -> uploadBack(edit) }
            .setNegativeButton(R.string.fileopen_not_now) { _, _ -> releaseManager(edit.sftpManager, edit.ownsManager) }
            .setOnCancelListener { releaseManager(edit.sftpManager, edit.ownsManager) }
            .show()
    }

    private fun uploadBack(edit: PendingEdit) {
        activity.lifecycleScope.launch {
            try {
                val task = withContext(Dispatchers.IO) {
                    edit.sftpManager.uploadFile(
                        localFile = edit.localFile,
                        remotePath = edit.remotePath,
                        listener = object : TransferListener {
                            override fun onProgress(transfer: TransferTask, bytesTransferred: Long, totalBytes: Long) {
                                NotificationHelper.showFileTransferProgress(
                                    activity, transfer.id.hashCode(), edit.localFile.name, bytesTransferred, totalBytes, isUpload = true
                                )
                            }
                        }
                    )
                }
                val result = awaitTransfer(task)
                NotificationHelper.cancelNotification(activity, task.id.hashCode())
                if (result is TransferResult.Success) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.fileopen_uploaded_fmt, edit.remotePath),
                        Toast.LENGTH_SHORT
                    ).show()
                    releaseManager(edit.sftpManager, edit.ownsManager)
                } else {
                    // Keep the local copy on disk — it already is, untouched —
                    // and re-prompt immediately so the user can retry or decline.
                    Toast.makeText(activity, R.string.fileopen_upload_failed_kept, Toast.LENGTH_LONG).show()
                    promptUploadBack(edit)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancellation is not a failure: leaving the screen mid-transfer
                // cancels this scope, and treating that as an error showed a
                // failure toast for a transfer the user simply walked away from.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Upload back failed for ${edit.remotePath}", e)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.sftp_upload_failed_fmt, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
                promptUploadBack(edit)
            }
        }
    }
}
