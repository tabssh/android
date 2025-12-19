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
        // TODO: Implement authentication dialog
        Logger.d(TAG, "Show authentication dialog")
    }

    /**
     * Show password setup dialog
     */
    private fun showPasswordSetupDialog() {
        // TODO: Implement password setup dialog
        Logger.d(TAG, "Show password setup dialog")
    }

    /**
     * Show account options dialog
     */
    private fun showAccountOptions() {
        // TODO: Implement account options dialog
        Logger.d(TAG, "Show account options dialog")
    }

    /**
     * Show clear data confirmation
     */
    private fun showClearDataConfirmation() {
        // TODO: Implement clear data confirmation dialog
        Logger.d(TAG, "Show clear data confirmation")
    }

    /**
     * Show force upload confirmation
     */
    private fun showForceUploadConfirmation() {
        // TODO: Implement force upload confirmation dialog
        Logger.d(TAG, "Show force upload confirmation")
    }

    /**
     * Show force download confirmation
     */
    private fun showForceDownloadConfirmation() {
        // TODO: Implement force download confirmation dialog
        Logger.d(TAG, "Show force download confirmation")
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
