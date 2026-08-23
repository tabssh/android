package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.sftp.SFTPManager
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wave 1.7 — minimal in-app text editor for remote files.
 *
 * Flow:
 *   1. Activity opens with a connection_id + remote_path.
 *   2. SFTP downloads the file to the app cache.
 *   3. Loads as plain text into a monospace EditText.
 *   4. "Save" pushes the edited text back via SFTP.
 *
 * Constraints:
 * - Files capped at 1 MiB by the caller (SFTPActivity enforces).
 * - Reads as UTF-8; non-UTF-8 bytes fall through Java's replacement.
 * - No syntax highlighting. Future polish.
 */
class RemoteFileEditorActivity : TabSSHActivity() {

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_REMOTE_PATH = "remote_path"
        const val EXTRA_FILE_NAME = "file_name"

        private const val TAG = "RemoteFileEditor"

        fun createIntent(context: Context, connectionId: String, remotePath: String, fileName: String): Intent =
            Intent(context, RemoteFileEditorActivity::class.java).apply {
                putExtra(EXTRA_CONNECTION_ID, connectionId)
                putExtra(EXTRA_REMOTE_PATH, remotePath)
                putExtra(EXTRA_FILE_NAME, fileName)
            }
    }

    private lateinit var app: TabSSHApplication
    private lateinit var editor: EditText
    private lateinit var progress: ProgressBar
    private var sftp: SFTPManager? = null
    private var remotePath: String = ""
    private var dirty: Boolean = false
    private var originalText: String = ""
    // Guard against double-save: tapping the Save action twice before the first
    // upload completes would race two concurrent SFTP writes against the same
    // path and overwrite the second with whatever finished last.
    private var isSaving: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dirty) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                promptUnsavedChanges()
            }
        })

        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)
        remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH).orEmpty()
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: remotePath.substringAfterLast('/')

        if (connectionId.isNullOrBlank() || remotePath.isBlank()) {
            Toast.makeText(this, R.string.remote_editor_missing_path, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Build UI programmatically — avoids a layout XML for one screen.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        // Shared app bar, inflated rather than hand-built so this programmatic
        // screen gets the same toolbar styling as every XML-defined screen.
        val appBar = layoutInflater.inflate(R.layout.include_app_bar, root, false)
        val toolbar = appBar.findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = fileName
        toolbar.subtitle = remotePath
        root.addView(appBar)

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER
            layoutParams = lp
        }
        root.addView(progress)

        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                // disables autocorrect/spellcheck
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            setPadding(24)
            setHorizontallyScrolling(true)
            // Fill remaining space.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val now = s?.toString().orEmpty()
                    if (now != originalText && !dirty) {
                        dirty = true
                        invalidateOptionsMenu()
                        toolbar.title = getString(R.string.remote_editor_title_dirty_fmt, fileName)
                    } else if (now == originalText && dirty) {
                        dirty = false
                        invalidateOptionsMenu()
                        toolbar.title = fileName
                    }
                }
            })
        }
        root.addView(editor)

        setContentView(root)
        setSupportActionBar(toolbar)

        // Connect SFTP and download.
        lifecycleScope.launch { downloadFile(connectionId) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.save).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setIcon(android.R.drawable.ic_menu_save)
            isEnabled = dirty
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(1)?.isEnabled = dirty && !isSaving
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { saveFile(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun promptUnsavedChanges() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_editor_unsaved_title)
            .setMessage(R.string.remote_editor_unsaved_message)
            .setPositiveButton(R.string.save) { _, _ -> saveFile { finish() } }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private suspend fun downloadFile(connectionId: String) {
        val ssh = app.sshSessionManager.getConnection(connectionId)
        if (ssh == null) {
            runOnUiThread {
                Toast.makeText(this, R.string.remote_editor_no_connection, Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        val mgr = SFTPManager(ssh)
        if (!mgr.connect()) {
            runOnUiThread {
                Toast.makeText(this, R.string.remote_editor_sftp_failed, Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        sftp = mgr

        val cacheFile = File(cacheDir, "edit_${System.currentTimeMillis()}_${File(remotePath).name}")
        try {
            val task = mgr.downloadFile(remotePath, cacheFile, null)
            // Poll until done (PENDING/ACTIVE → COMPLETED/ERROR/CANCELLED).
            while (true) {
                when (task.state.value) {
                    io.github.tabssh.sftp.TransferState.COMPLETED -> break
                    io.github.tabssh.sftp.TransferState.ERROR,
                    io.github.tabssh.sftp.TransferState.CANCELLED -> {
                        runOnUiThread {
                            Toast.makeText(this@RemoteFileEditorActivity, R.string.remote_editor_download_failed, Toast.LENGTH_LONG).show()
                            finish()
                        }
                        return
                    }
                    else -> kotlinx.coroutines.delay(100)
                }
            }
            if (!cacheFile.exists()) {
                runOnUiThread {
                    Toast.makeText(this, R.string.remote_editor_download_empty, Toast.LENGTH_LONG).show()
                    finish()
                }
                return
            }
            val text = withContext(Dispatchers.IO) { cacheFile.readText(Charsets.UTF_8) }
            originalText = text
            runOnUiThread {
                editor.setText(text)
                progress.visibility = android.view.View.GONE
                dirty = false
                invalidateOptionsMenu()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Download failed", e)
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(R.string.remote_editor_error_fmt, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun saveFile(onComplete: (() -> Unit)? = null) {
        val mgr = sftp ?: return
        // Reject re-entry while an upload is already in flight — otherwise two
        // concurrent uploads race and the second clobbers the first.
        if (isSaving) {
            Toast.makeText(this, R.string.remote_editor_save_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        isSaving = true
        invalidateOptionsMenu()
        val text = editor.text?.toString().orEmpty()
        val tmpFile = File(cacheDir, "save_${System.currentTimeMillis()}_${File(remotePath).name}")
        progress.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    tmpFile.writeText(text, Charsets.UTF_8)
                }
                val task = mgr.uploadFile(tmpFile, remotePath, null)
                var success = false
                while (true) {
                    when (task.state.value) {
                        io.github.tabssh.sftp.TransferState.COMPLETED -> { success = true; break }
                        io.github.tabssh.sftp.TransferState.ERROR,
                        io.github.tabssh.sftp.TransferState.CANCELLED -> break
                        else -> kotlinx.coroutines.delay(100)
                    }
                }
                runOnUiThread {
                    progress.visibility = android.view.View.GONE
                    if (success) {
                        Toast.makeText(this@RemoteFileEditorActivity, R.string.remote_editor_saved, Toast.LENGTH_SHORT).show()
                        originalText = text
                        dirty = false
                        invalidateOptionsMenu()
                        onComplete?.invoke()
                    } else {
                        Toast.makeText(this@RemoteFileEditorActivity, R.string.remote_editor_upload_failed, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Save failed", e)
                runOnUiThread {
                    progress.visibility = android.view.View.GONE
                    Toast.makeText(
                        this@RemoteFileEditorActivity,
                        getString(R.string.remote_editor_error_fmt, e.message.orEmpty()),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                tmpFile.delete()
                runOnUiThread {
                    isSaving = false
                    invalidateOptionsMenu()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sftp?.disconnect()
    }
}
