package io.github.tabssh.ui.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.sync.SAFSyncManager
import io.github.tabssh.sync.SyncFileStatus
import io.github.tabssh.sync.data.SyncDataCollector
import io.github.tabssh.sync.data.SyncDataApplier
import io.github.tabssh.sync.worker.SyncWorkScheduler
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment for sync settings using Storage Access Framework (SAF)
 *
 * Supports syncing to any cloud storage provider:
 * - Google Drive app
 * - Dropbox
 * - OneDrive
 * - Nextcloud
 * - Local storage
 * - Any DocumentsProvider
 */
class SyncSettingsFragment : PreferenceFragmentCompat() {

    companion object {
        private const val TAG = "SyncSettingsFragment"
    }

    private lateinit var syncManager: SAFSyncManager
    private lateinit var workScheduler: SyncWorkScheduler
    private lateinit var app: TabSSHApplication

    // Activity Result Launchers
    private lateinit var createFileLauncher: ActivityResultLauncher<Intent>
    private lateinit var openFileLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register file picker launchers BEFORE onCreatePreferences
        createFileLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    Logger.i(TAG, "Created sync file: $uri")
                    syncManager.saveSyncUri(uri)
                    updateLocationSummary()
                    updateSyncStatus()
                    showToast("Sync location configured")
                }
            } else {
                Logger.d(TAG, "Create file cancelled")
            }
        }

        openFileLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    Logger.i(TAG, "Opened sync file: $uri")
                    syncManager.saveSyncUri(uri)
                    updateLocationSummary()
                    updateSyncStatus()
                    showToast("Sync location configured")
                }
            } else {
                Logger.d(TAG, "Open file cancelled")
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_sync, rootKey)

        app = requireActivity().application as TabSSHApplication
        syncManager = SAFSyncManager(requireContext())
        workScheduler = SyncWorkScheduler(requireContext())

        setupLocationPreference()
        setupPasswordPreference()
        setupSyncToggle()
        setupFrequencyPreference()
        setupManualSync()
        setupAdvancedOptions()

        updateLocationSummary()
        updateSyncStatus()
        updateLastSyncTime()
    }

    /**
     * Setup sync location preference (file picker)
     */
    private fun setupLocationPreference() {
        findPreference<Preference>("sync_location")?.apply {
            setOnPreferenceClickListener {
                showLocationOptions()
                true
            }
        }
    }

    /**
     * Show location options dialog
     */
    private fun showLocationOptions() {
        val hasExisting = syncManager.getSyncUri() != null

        val options = if (hasExisting) {
            arrayOf(
                "Create new sync file",
                "Open existing sync file",
                "Clear current location"
            )
        } else {
            arrayOf(
                "Create new sync file",
                "Open existing sync file"
            )
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Sync Location")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createFileLauncher.launch(syncManager.getCreateFileIntent())
                    1 -> openFileLauncher.launch(syncManager.getOpenFileIntent())
                    2 -> {
                        syncManager.clearConfiguration()
                        findPreference<SwitchPreferenceCompat>("sync_enabled")?.isChecked = false
                        updateLocationSummary()
                        updateSyncStatus()
                        showToast("Sync location cleared")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Update location summary to show current sync location
     */
    private fun updateLocationSummary() {
        findPreference<Preference>("sync_location")?.apply {
            val locationName = syncManager.getSyncLocationName()
            summary = locationName ?: "Not configured - tap to choose"
        }
    }

    /**
     * Setup sync password preference
     */
    private fun setupPasswordPreference() {
        findPreference<Preference>("sync_password")?.apply {
            setOnPreferenceClickListener {
                showPasswordDialog()
                true
            }
            updatePasswordSummary()
        }
    }

    /**
     * Update password summary
     */
    private fun updatePasswordSummary() {
        findPreference<Preference>("sync_password")?.apply {
            summary = if (syncManager.hasPassword()) {
                "Password set (tap to change)"
            } else {
                "Required - tap to set password"
            }
        }
    }

    /**
     * Show password setup dialog
     */
    private fun showPasswordDialog() {
        val errorText = android.widget.TextView(requireContext()).apply {
            setTextColor(android.graphics.Color.RED)
            setPadding(0, 16, 0, 0)
            visibility = View.GONE
        }

        val passwordInput = android.widget.EditText(requireContext()).apply {
            hint = "Enter encryption password (min 8 chars)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val confirmInput = android.widget.EditText(requireContext()).apply {
            hint = "Confirm password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
            addView(passwordInput)
            addView(confirmInput)
            addView(errorText)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Set Encryption Password")
            .setMessage("This password encrypts your synced data. Use the same password on all devices.")
            .setView(layout)
            .setPositiveButton("Set Password", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = passwordInput.text.toString()
            val confirm = confirmInput.text.toString()

            when {
                password.length < 8 -> {
                    errorText.text = "Password must be at least 8 characters"
                    errorText.visibility = View.VISIBLE
                }
                password != confirm -> {
                    errorText.text = "Passwords do not match"
                    errorText.visibility = View.VISIBLE
                }
                else -> {
                    syncManager.setSyncPassword(password)
                    updatePasswordSummary()
                    updateSyncStatus()
                    showToast("Encryption password set")
                    dialog.dismiss()
                }
            }
        }
    }

    /**
     * Setup sync enable/disable toggle
     */
    private fun setupSyncToggle() {
        findPreference<SwitchPreferenceCompat>("sync_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean

                if (enabled) {
                    // Check requirements
                    if (syncManager.getSyncUri() == null) {
                        showSetupError("Sync location not set", "Please choose a sync location first.")
                        return@setOnPreferenceChangeListener false
                    }
                    if (!syncManager.hasPassword()) {
                        showSetupError("Password not set", "Please set an encryption password first.")
                        return@setOnPreferenceChangeListener false
                    }

                    // Verify file is accessible
                    lifecycleScope.launch {
                        val status = syncManager.checkSyncFile()
                        withContext(Dispatchers.Main) {
                            when (status) {
                                SyncFileStatus.OK -> {
                                    workScheduler.schedulePeriodicSync()
                                    updateSyncStatus()
                                    showToast("Sync enabled")
                                }
                                SyncFileStatus.FILE_NOT_FOUND -> {
                                    showSetupError("File not found", "The sync file no longer exists. Please reconfigure.")
                                    isChecked = false
                                }
                                SyncFileStatus.NO_PERMISSION -> {
                                    showSetupError("Permission denied", "Cannot access sync file. Please reconfigure.")
                                    isChecked = false
                                }
                                else -> {
                                    showSetupError("Error", "Could not verify sync file: $status")
                                    isChecked = false
                                }
                            }
                        }
                    }
                    true
                } else {
                    workScheduler.cancelPeriodicSync()
                    updateSyncStatus()
                    true
                }
            }
        }
    }

    /**
     * Update sync status display
     */
    private fun updateSyncStatus() {
        findPreference<Preference>("sync_status")?.apply {
            val hasLocation = syncManager.getSyncUri() != null
            val hasPassword = syncManager.hasPassword()
            val isEnabled = findPreference<SwitchPreferenceCompat>("sync_enabled")?.isChecked == true

            summary = when {
                !hasLocation && !hasPassword -> "Setup required: Choose location and set password"
                !hasLocation -> "Setup required: Choose sync location"
                !hasPassword -> "Setup required: Set encryption password"
                isEnabled -> "Sync is active"
                else -> "Ready to enable sync"
            }

            setOnPreferenceClickListener {
                showStatusDialog()
                true
            }
        }

        // Update toggle summary
        findPreference<SwitchPreferenceCompat>("sync_enabled")?.apply {
            summary = if (syncManager.isConfigured()) {
                "Synchronize data across devices"
            } else {
                "Complete setup above first"
            }
        }
    }

    /**
     * Show status dialog with details
     */
    private fun showStatusDialog() {
        lifecycleScope.launch {
            val fileStatus = syncManager.checkSyncFile()
            val locationName = syncManager.getSyncLocationName() ?: "Not configured"
            val hasPassword = syncManager.hasPassword()
            val lastSync = syncManager.getLastSyncTime()
            val isEnabled = findPreference<SwitchPreferenceCompat>("sync_enabled")?.isChecked == true

            val message = buildString {
                append("Location: $locationName\n")
                append("Password: ${if (hasPassword) "Set" else "Not set"}\n")
                append("File status: $fileStatus\n")
                append("Sync enabled: ${if (isEnabled) "Yes" else "No"}\n")
                append("Last sync: ${formatLastSync(lastSync)}\n")
            }

            withContext(Dispatchers.Main) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Sync Status")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /**
     * Setup sync frequency preference
     */
    private fun setupFrequencyPreference() {
        findPreference<ListPreference>("sync_frequency")?.apply {
            setOnPreferenceChangeListener { _, _ ->
                workScheduler.schedulePeriodicSync()
                true
            }
        }
    }

    /**
     * Setup manual sync trigger
     */
    private fun setupManualSync() {
        findPreference<Preference>("sync_manual_trigger")?.apply {
            setOnPreferenceClickListener {
                performSync()
                true
            }
        }
    }

    /**
     * Perform manual sync
     */
    private fun performSync() {
        if (!syncManager.isConfigured()) {
            showToast("Sync not configured")
            return
        }

        showToast("Syncing...")

        lifecycleScope.launch {
            try {
                // Collect local data
                val collector = SyncDataCollector(requireContext())
                val payload = collector.collectAll()

                // Upload
                val success = syncManager.upload(payload)

                withContext(Dispatchers.Main) {
                    if (success) {
                        showToast("Sync completed")
                        updateLastSyncTime()
                    } else {
                        showToast("Sync failed")
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Sync failed", e)
                withContext(Dispatchers.Main) {
                    showToast("Sync failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Setup advanced options
     */
    private fun setupAdvancedOptions() {
        findPreference<Preference>("sync_force_upload")?.setOnPreferenceClickListener {
            showForceUploadDialog()
            true
        }

        findPreference<Preference>("sync_force_download")?.setOnPreferenceClickListener {
            showForceDownloadDialog()
            true
        }

        findPreference<Preference>("sync_clear_config")?.setOnPreferenceClickListener {
            showClearConfigDialog()
            true
        }
    }

    /**
     * Show force upload confirmation
     */
    private fun showForceUploadDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Force Upload")
            .setMessage("This will upload your local data to the sync file, overwriting any existing data.\n\nContinue?")
            .setPositiveButton("Upload") { _, _ ->
                performSync()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show force download confirmation
     */
    private fun showForceDownloadDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Force Download")
            .setMessage("This will download data from the sync file and overwrite your local data.\n\nWARNING: All local changes will be lost!\n\nContinue?")
            .setPositiveButton("Download") { _, _ ->
                performDownload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Perform download
     */
    private fun performDownload() {
        if (!syncManager.isConfigured()) {
            showToast("Sync not configured")
            return
        }

        showToast("Downloading...")

        lifecycleScope.launch {
            try {
                val payload = syncManager.download()

                if (payload != null) {
                    val applier = SyncDataApplier(requireContext())
                    applier.applyAll(payload)

                    withContext(Dispatchers.Main) {
                        showToast("Download completed")
                        updateLastSyncTime()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showToast("Download failed or file is empty")
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    showToast("Download failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Show clear config confirmation
     */
    private fun showClearConfigDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Sync Configuration")
            .setMessage("This will remove your sync configuration. You will need to set up sync again.\n\nYour local data will NOT be affected.")
            .setPositiveButton("Clear") { _, _ ->
                syncManager.clearConfiguration()
                findPreference<SwitchPreferenceCompat>("sync_enabled")?.isChecked = false
                workScheduler.cancelPeriodicSync()
                updateLocationSummary()
                updatePasswordSummary()
                updateSyncStatus()
                updateLastSyncTime()
                showToast("Sync configuration cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Update last sync time display
     */
    private fun updateLastSyncTime() {
        findPreference<Preference>("sync_last_time")?.apply {
            summary = formatLastSync(syncManager.getLastSyncTime())
        }
    }

    /**
     * Format last sync time
     */
    private fun formatLastSync(timestamp: Long): String {
        if (timestamp == 0L) return "Never"

        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            else -> "${diff / 86400_000} days ago"
        }
    }

    /**
     * Show setup error dialog
     */
    private fun showSetupError(title: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
