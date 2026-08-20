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
        val originalSize: Long
    )

    private var pendingEdit: PendingEdit? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        checkPendingEditForChanges()
    }

    /**
     * Stats [remotePath] over [sftpManager], applies the size gate, downloads
     * it, then launches an external viewer with a writable URI grant. The
     * file is tracked for the upload-back prompt on resume.
     */
    fun open(sftpManager: SFTPManager, remotePath: String, displayName: String) {
        activity.lifecycleScope.launch {
            val stat = withContext(Dispatchers.IO) { sftpManager.getRemoteFileAttributes(remotePath) }
            if (stat == null) {
                Toast.makeText(activity, R.string.fileopen_stat_failed, Toast.LENGTH_LONG).show()
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
                    .setPositiveButton(R.string.download_file) { _, _ -> downloadAndOpen(sftpManager, remotePath, displayName) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                downloadAndOpen(sftpManager, remotePath, displayName)
            }
        }
    }

    private fun downloadAndOpen(sftpManager: SFTPManager, remotePath: String, displayName: String) {
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
                        activity.getString(R.string.fileopen_download_failed_fmt, message),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                withContext(Dispatchers.IO) { evictCache(cacheSubDir) }
                launchViewer(sftpManager, remotePath, localFile)
            } catch (e: Exception) {
                Logger.e(TAG, "Download failed for $remotePath", e)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.fileopen_download_failed_fmt, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
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

    private fun launchViewer(sftpManager: SFTPManager, remotePath: String, localFile: File) {
        val uri: Uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", localFile)
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "FileProvider could not create a URI for ${localFile.name}", e)
            Toast.makeText(activity, R.string.fileopen_open_failed, Toast.LENGTH_LONG).show()
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
            pendingEdit = PendingEdit(localFile, remotePath, sftpManager, localFile.lastModified(), localFile.length())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.fileopen_no_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPendingEditForChanges() {
        val edit = pendingEdit ?: return
        if (!edit.localFile.exists()) {
            pendingEdit = null
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
            .setNegativeButton(R.string.fileopen_not_now, null)
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
                } else {
                    // Keep the local copy on disk — it already is, untouched —
                    // and re-prompt immediately so the user can retry or decline.
                    Toast.makeText(activity, R.string.fileopen_upload_failed_kept, Toast.LENGTH_LONG).show()
                    promptUploadBack(edit)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Upload back failed for ${edit.remotePath}", e)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.fileopen_upload_failed_fmt, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
                promptUploadBack(edit)
            }
        }
    }
}
