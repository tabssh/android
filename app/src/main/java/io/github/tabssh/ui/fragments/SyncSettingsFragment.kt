package io.github.tabssh.ui.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.tabssh.R
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.GoogleDriveSyncManager
import io.github.tabssh.sync.auth.DriveAuthenticationManager
import io.github.tabssh.sync.worker.SyncWorkScheduler
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Fragment for Google Drive sync settings
 */
class SyncSettingsFragment : PreferenceFragmentCompat() {

    companion object {
        private const val TAG = "SyncSettingsFragment"
    }

    private lateinit var preferenceManager: io.github.tabssh.storage.preferences.PreferenceManager
    private lateinit var syncManager: GoogleDriveSyncManager
    private lateinit var workScheduler: SyncWorkScheduler

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_sync, rootKey)

        preferenceManager = io.github.tabssh.storage.preferences.PreferenceManager(requireContext())
        syncManager = GoogleDriveSyncManager(requireContext())
        workScheduler = SyncWorkScheduler(requireContext())

        setupSyncToggle()
        setupAccountPreference()
        setupPasswordPreference()
        setupManualSync()
        setupLastSyncTime()
        setupFrequencyPreference()
        setupAdvancedOptions()
    }

    /**
     * Setup sync enable/disable toggle
     */
    private fun setupSyncToggle() {
        findPreference<SwitchPreferenceCompat>("sync_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean

                if (enabled) {
                    if (!syncManager.getAuthManager().isAuthenticated()) {
                        showAuthenticationDialog()
                        false
                    } else if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean("sync_password_set", false)) {
                        showPasswordSetupDialog()
                        false
                    } else {
                        enableSync()
                        true
                    }
                } else {
                    disableSync()
                    true
                }
            }
        }
    }

    /**
     * Setup Google account preference
     */
    private fun setupAccountPreference() {
        findPreference<Preference>("sync_account")?.apply {
            setOnPreferenceClickListener {
                if (syncManager.getAuthManager().isAuthenticated()) {
                    showAccountOptions()
                } else {
                    signIn()
                }
                true
            }

            updateAccountSummary()
        }
    }

    /**
     * Setup sync password preference
     */
    private fun setupPasswordPreference() {
        findPreference<Preference>("sync_password")?.apply {
            setOnPreferenceClickListener {
                showPasswordSetupDialog()
                true
            }

            summary = if (androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean("sync_password_set", false)) {
                "Password configured"
            } else {
                "Not configured"
            }
        }
    }

    /**
     * Setup manual sync trigger
     */
    private fun setupManualSync() {
        findPreference<Preference>("sync_manual_trigger")?.apply {
            setOnPreferenceClickListener {
                triggerManualSync()
                true
            }
        }
    }

    /**
     * Setup last sync time display
     */
    private fun setupLastSyncTime() {
        findPreference<Preference>("sync_last_time")?.apply {
            updateLastSyncSummary()
        }
    }

    /**
     * Setup sync frequency preference
     */
    private fun setupFrequencyPreference() {
        findPreference<ListPreference>("sync_frequency")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val frequency = newValue as String
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putString("sync_frequency", frequency)
                    .apply()
                workScheduler.schedulePeriodicSync()
                true
            }
        }
    }

    /**
     * Setup advanced options
     */
    private fun setupAdvancedOptions() {
        findPreference<Preference>("sync_clear_data")?.apply {
            setOnPreferenceClickListener {
                showClearDataConfirmation()
                true
            }
        }

        findPreference<Preference>("sync_force_upload")?.apply {
            setOnPreferenceClickListener {
                showForceUploadConfirmation()
                true
            }
        }

        findPreference<Preference>("sync_force_download")?.apply {
            setOnPreferenceClickListener {
                showForceDownloadConfirmation()
                true
            }
        }
    }

    /**
     * Sign in to Google Drive
     */
    private fun signIn() {
        syncManager.getAuthManager().signIn(requireActivity())
    }

    /**
     * Enable sync
     */
    private fun enableSync() {
        lifecycleScope.launch {
            try {
                workScheduler.schedulePeriodicSync()
                Logger.d(TAG, "Sync enabled")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to enable sync", e)
            }
        }
    }

    /**
     * Disable sync
     */
    private fun disableSync() {
        lifecycleScope.launch {
            try {
                syncManager.disableSync()
                workScheduler.cancelPeriodicSync()
                Logger.d(TAG, "Sync disabled")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to disable sync", e)
            }
        }
    }

    /**
     * Trigger manual sync
     */
    private fun triggerManualSync() {
        lifecycleScope.launch {
            try {
                workScheduler.scheduleImmediateSync()
                Logger.d(TAG, "Manual sync triggered")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to trigger manual sync", e)
            }
        }
    }

    /**
     * Update account summary
     */
    private fun updateAccountSummary() {
        findPreference<Preference>("sync_account")?.apply {
            summary = syncManager.getAuthManager().getAccountEmail() ?: "Not connected"
        }
    }

    /**
     * Update last sync time summary
     */
    private fun updateLastSyncSummary() {
        findPreference<Preference>("sync_last_time")?.apply {
            val lastSync = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext()).getLong("sync_last_time", 0L)
            summary = if (lastSync == 0L) {
                "Never synced"
            } else {
                val timeDiff = System.currentTimeMillis() - lastSync
                when {
                    timeDiff < 60_000L -> "Just now"
                    timeDiff < 3600_000L -> "${timeDiff / 60_000} minutes ago"
                    timeDiff < 86400_000L -> "${timeDiff / 3600_000} hours ago"
                    else -> "${timeDiff / 86400_000} days ago"
                }
            }
        }
    }

    /**
     * Show authentication dialog
     */
    private fun showAuthenticationDialog() {
        Logger.d(TAG, "Show authentication dialog")
        
        val backend = preferenceManager.getSyncBackend()
        
        when (backend) {
            "google_drive" -> {
                // Launch Google Sign-In flow
                try {
                    val authManager = syncManager.getAuthManager()
                    authManager.signIn(requireActivity())
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to start Google Sign-In", e)
                    android.widget.Toast.makeText(requireContext(), "Failed to start Google Sign-In: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            "webdav" -> {
                // WebDAV uses username/password from preferences, no separate auth dialog needed
                android.widget.Toast.makeText(requireContext(), "WebDAV authentication uses server credentials from settings", android.widget.Toast.LENGTH_SHORT).show()
                updateAccountSummary()
            }
            else -> {
                Logger.w(TAG, "Unknown sync backend: $backend")
            }
        }
    }

    /**
     * Show password setup dialog
     */
    private fun showPasswordSetupDialog() {
        Logger.d(TAG, "Show password setup dialog")
        
        val editText = android.widget.EditText(requireContext()).apply {
            hint = "Enter encryption password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        val confirmEditText = android.widget.EditText(requireContext()).apply {
            hint = "Confirm password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
            addView(editText)
            addView(confirmEditText)
        }
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Set Encryption Password")
            .setMessage("This password will be used to encrypt your synced data. Keep it safe!")
            .setView(layout)
            .setPositiveButton("Set Password") { _, _ ->
                val password = editText.text.toString()
                val confirm = confirmEditText.text.toString()
                
                when {
                    password.isBlank() -> {
                        android.widget.Toast.makeText(requireContext(), "Password cannot be empty", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    password.length < 8 -> {
                        android.widget.Toast.makeText(requireContext(), "Password must be at least 8 characters", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    password != confirm -> {
                        android.widget.Toast.makeText(requireContext(), "Passwords do not match", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Save password
                        syncManager.setSyncPassword(password)
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit()
                            .putBoolean("sync_password_set", true)
                            .apply()
                        
                        android.widget.Toast.makeText(requireContext(), "Encryption password set successfully", android.widget.Toast.LENGTH_SHORT).show()
                        findPreference<Preference>("sync_password")?.summary = "Password set (tap to change)"
                        Logger.i(TAG, "Sync encryption password set")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show account options dialog
     */
    private fun showAccountOptions() {
        Logger.d(TAG, "Show account options dialog")
        
        val options = arrayOf("Sign out", "Re-authenticate", "Account info")
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Account Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // Sign out
                        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Sign Out")
                            .setMessage("Are you sure you want to sign out? You will need to authenticate again to use sync.")
                            .setPositiveButton("Sign Out") { _, _ ->
                                lifecycleScope.launch {
                                    try {
                                        syncManager.getAuthManager().signOut()
                                        android.widget.Toast.makeText(requireContext(), "Signed out successfully", android.widget.Toast.LENGTH_SHORT).show()
                                        updateAccountSummary()
                                        Logger.i(TAG, "User signed out from sync")
                                    } catch (e: Exception) {
                                        Logger.e(TAG, "Failed to sign out", e)
                                        android.widget.Toast.makeText(requireContext(), "Error signing out: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    1 -> {
                        // Re-authenticate
                        showAuthenticationDialog()
                    }
                    2 -> {
                        // Account info
                        lifecycleScope.launch {
                            val account = syncManager.getAuthManager().getCurrentAccount()
                            val info = if (account != null) {
                                """
                                Email: ${account.email}
                                Display Name: ${account.displayName ?: "N/A"}
                                Backend: ${preferenceManager.getSyncBackend()}
                                """.trimIndent()
                            } else {
                                "No account connected"
                            }
                            
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Account Information")
                                .setMessage(info)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show clear data confirmation
     */
    private fun showClearDataConfirmation() {
        Logger.d(TAG, "Show clear data confirmation")
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Remote Data")
            .setMessage("This will permanently delete all sync data from Google Drive. Your local data will not be affected.\n\nAre you sure?")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    try {
                        syncManager.clearRemoteData()
                        android.widget.Toast.makeText(requireContext(), "Remote data cleared", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to clear remote data", e)
                        android.widget.Toast.makeText(requireContext(), "Failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show force upload confirmation
     */
    private fun showForceUploadConfirmation() {
        Logger.d(TAG, "Show force upload confirmation")
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Force Upload")
            .setMessage("This will upload your local data to Google Drive, overwriting any existing remote data.\n\nContinue?")
            .setPositiveButton("Upload") { _, _ ->
                lifecycleScope.launch {
                    val success = syncManager.forceUpload()
                    if (success) {
                        android.widget.Toast.makeText(requireContext(), "Upload successful", android.widget.Toast.LENGTH_SHORT).show()
                        setupLastSyncTime()
                    } else {
                        android.widget.Toast.makeText(requireContext(), "Upload failed", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show force download confirmation
     */
    private fun showForceDownloadConfirmation() {
        Logger.d(TAG, "Show force download confirmation")
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Force Download")
            .setMessage("This will download remote data from Google Drive and overwrite your local data.\n\n⚠️ WARNING: All local changes will be lost!\n\nContinue?")
            .setPositiveButton("Download") { _, _ ->
                lifecycleScope.launch {
                    val success = syncManager.forceDownload()
                    if (success) {
                        android.widget.Toast.makeText(requireContext(), "Download successful", android.widget.Toast.LENGTH_SHORT).show()
                        setupLastSyncTime()
                    } else {
                        android.widget.Toast.makeText(requireContext(), "Download failed", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == DriveAuthenticationManager.REQUEST_CODE_SIGN_IN) {
            if (resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch {
                    val result = syncManager.getAuthManager().handleSignInResult(data)
                    updateAccountSummary()
                }
            }
        }
    }
}
