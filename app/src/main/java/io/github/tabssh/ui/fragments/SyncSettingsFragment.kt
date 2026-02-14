package io.github.tabssh.ui.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.tabssh.R
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.GoogleDriveSyncManager
import io.github.tabssh.sync.auth.DriveAuthenticationManager
import io.github.tabssh.sync.auth.SignInResult
import io.github.tabssh.sync.worker.SyncWorkScheduler
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.showError

/**
 * Fragment for Google Drive sync settings
 */
class SyncSettingsFragment : PreferenceFragmentCompat() {

    companion object {
        private const val TAG = "SyncSettingsFragment"
    }

    private lateinit var prefManager: io.github.tabssh.storage.preferences.PreferenceManager
    private lateinit var syncManager: GoogleDriveSyncManager
    private lateinit var workScheduler: SyncWorkScheduler

    // Activity Result Launcher for Google Sign-In
    private lateinit var signInLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the sign-in launcher BEFORE onCreatePreferences
        signInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch {
                    try {
                        val signInResult = syncManager.getAuthManager().handleSignInResult(result.data)
                        when (signInResult) {
                            is SignInResult.Success -> {
                                Logger.i(TAG, "Google Sign-In successful: ${signInResult.account.email}")
                                showToast("Signed in as ${signInResult.account.email}")
                                updateAccountSummary()
                                updateSyncStatus()
                            }
                            is SignInResult.Error -> {
                                Logger.e(TAG, "Google Sign-In failed: ${signInResult.message}")
                                showSignInError(signInResult.message)
                            }
                            is SignInResult.Cancelled -> {
                                Logger.i(TAG, "Google Sign-In cancelled")
                                showToast("Sign-in cancelled")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error handling sign-in result", e)
                        showSignInError("Error: ${e.message}")
                    }
                }
            } else {
                Logger.w(TAG, "Sign-in result not OK: ${result.resultCode}")
                showToast("Sign-in cancelled or failed")
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_sync, rootKey)

        prefManager = io.github.tabssh.storage.preferences.PreferenceManager(requireContext())
        syncManager = GoogleDriveSyncManager(requireContext())
        workScheduler = SyncWorkScheduler(requireContext())

        setupBackendPreference()
        setupSyncStatus()
        setupSyncToggle()
        setupAccountPreference()
        setupPasswordPreference()
        setupManualSync()
        setupLastSyncTime()
        setupFrequencyPreference()
        setupAdvancedOptions()
        setupWebDAVPreferences()

        // Initial visibility update
        updatePreferencesVisibility()
        updateSyncStatus()
    }

    /**
     * Setup sync status preference that shows what's configured/missing
     */
    private fun setupSyncStatus() {
        findPreference<Preference>("sync_status")?.apply {
            setOnPreferenceClickListener {
                showSyncSetupDialog()
                true
            }
        }
    }

    /**
     * Update sync status summary to show what's configured
     */
    private fun updateSyncStatus() {
        val backend = prefManager.getString("sync_backend", "auto")
        val hasGooglePlay = try {
            requireContext().packageManager.getPackageInfo("com.google.android.gms", 0)
            true
        } catch (e: Exception) {
            false
        }

        val actualBackend = if (backend == "auto") {
            if (hasGooglePlay) "google_drive" else "webdav"
        } else {
            backend
        }

        val isGoogleDrive = actualBackend == "google_drive"
        val isAuthenticated = syncManager.getAuthManager().isAuthenticated()
        val hasPassword = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean("sync_password_set", false)
        val webdavUrl = prefManager.getString("webdav_server_url", "")
        val webdavUsername = prefManager.getString("webdav_username", "")
        val webdavPassword = prefManager.getString("webdav_password", "")
        val hasWebDAV = !webdavUrl.isNullOrEmpty() && !webdavUsername.isNullOrEmpty() && !webdavPassword.isNullOrEmpty()

        findPreference<Preference>("sync_status")?.apply {
            val statusParts = mutableListOf<String>()
            val missingParts = mutableListOf<String>()

            // Check backend status
            if (isGoogleDrive) {
                if (isAuthenticated) {
                    statusParts.add("✓ Google account connected")
                } else {
                    missingParts.add("Google account not connected")
                }
            } else {
                if (hasWebDAV) {
                    statusParts.add("✓ WebDAV configured")
                } else {
                    missingParts.add("WebDAV not configured")
                }
            }

            // Check encryption password
            if (hasPassword) {
                statusParts.add("✓ Encryption password set")
            } else {
                missingParts.add("Encryption password not set")
            }

            summary = if (missingParts.isEmpty()) {
                "✓ Ready to enable sync"
            } else {
                "✗ Missing: ${missingParts.joinToString(", ")}"
            }
        }

        // Also update the toggle summary
        findPreference<SwitchPreferenceCompat>("sync_enabled")?.summary = if (
            (isGoogleDrive && isAuthenticated && hasPassword) ||
            (!isGoogleDrive && hasWebDAV && hasPassword)
        ) {
            "Synchronize data across devices"
        } else {
            "Configure settings above first"
        }
    }

    /**
     * Show sync setup dialog with requirements checklist
     */
    private fun showSyncSetupDialog() {
        val backend = prefManager.getString("sync_backend", "auto")
        val hasGooglePlay = try {
            requireContext().packageManager.getPackageInfo("com.google.android.gms", 0)
            true
        } catch (e: Exception) {
            false
        }

        val actualBackend = if (backend == "auto") {
            if (hasGooglePlay) "google_drive" else "webdav"
        } else {
            backend
        }

        val isGoogleDrive = actualBackend == "google_drive"
        val isAuthenticated = syncManager.getAuthManager().isAuthenticated()
        val hasPassword = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean("sync_password_set", false)
        val webdavUrl = prefManager.getString("webdav_server_url", "")
        val webdavUsername = prefManager.getString("webdav_username", "")
        val webdavPassword = prefManager.getString("webdav_password", "")
        val hasWebDAV = !webdavUrl.isNullOrEmpty() && !webdavUsername.isNullOrEmpty() && !webdavPassword.isNullOrEmpty()

        val message = buildString {
            append("Setup Requirements:\n\n")

            if (isGoogleDrive) {
                append("Backend: Google Drive\n\n")
                val accountIcon = if (isAuthenticated) "✓" else "✗"
                append("$accountIcon Google Account: ${if (isAuthenticated) "Connected" else "Not connected"}\n")
                if (!isAuthenticated) {
                    append("   → Tap 'Google account' below to sign in\n")
                }
            } else {
                append("Backend: WebDAV")
                if (backend == "auto") append(" (auto-detected, no Google Play)")
                append("\n\n")
                val webdavIcon = if (hasWebDAV) "✓" else "✗"
                append("$webdavIcon WebDAV Server: ${if (hasWebDAV) "Configured" else "Not configured"}\n")
                if (!hasWebDAV) {
                    append("   → Fill in WebDAV settings below\n")
                }
            }

            val passwordIcon = if (hasPassword) "✓" else "✗"
            append("\n$passwordIcon Encryption Password: ${if (hasPassword) "Set" else "Not set"}\n")
            if (!hasPassword) {
                append("   → Tap 'Sync encryption password' below\n")
            }

            append("\n")
            if ((isGoogleDrive && isAuthenticated && hasPassword) || (!isGoogleDrive && hasWebDAV && hasPassword)) {
                append("✓ All requirements met! You can now enable sync.")
            } else {
                append("Complete the items above to enable sync.")
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Sync Setup")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    /**
     * Setup backend selection
     */
    private fun setupBackendPreference() {
        findPreference<ListPreference>("sync_backend")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                updatePreferencesVisibility(newValue as String)
                true
            }
        }
    }
    
    /**
     * Update preferences visibility based on backend
     */
    private fun updatePreferencesVisibility(backend: String? = null) {
        val selectedBackend = backend ?: prefManager.getString("sync_backend", "auto")

        // Detect actual backend for "auto" mode
        val actualBackend = if (selectedBackend == "auto") {
            val hasGooglePlay = try {
                val packageManager = requireContext().packageManager
                packageManager.getPackageInfo("com.google.android.gms", 0)
                true
            } catch (e: Exception) {
                false
            }
            if (hasGooglePlay) "google_drive" else "webdav"
        } else {
            selectedBackend
        }

        val isGoogleDrive = actualBackend == "google_drive"
        val isWebDAV = actualBackend == "webdav"

        // Google Drive preferences - show when using Google Drive
        findPreference<Preference>("sync_account")?.isVisible = isGoogleDrive
        findPreference<EditTextPreference>("google_drive_sync_folder")?.isVisible = isGoogleDrive

        // WebDAV preferences - show when using WebDAV
        findPreference<EditTextPreference>("webdav_server_url")?.isVisible = isWebDAV
        findPreference<EditTextPreference>("webdav_username")?.isVisible = isWebDAV
        findPreference<EditTextPreference>("webdav_password")?.isVisible = isWebDAV
        findPreference<EditTextPreference>("webdav_sync_folder")?.isVisible = isWebDAV
        findPreference<Preference>("webdav_test_connection")?.isVisible = isWebDAV

        // Update status after visibility changes
        updateSyncStatus()
    }
    
    /**
     * Setup WebDAV preferences
     */
    private fun setupWebDAVPreferences() {
        findPreference<Preference>("webdav_test_connection")?.apply {
            setOnPreferenceClickListener {
                testWebDAVConnection()
                true
            }
        }
    }
    
    /**
     * Test WebDAV connection
     */
    private fun testWebDAVConnection() {
        lifecycleScope.launch {
            try {
                val url = prefManager.getString("webdav_server_url", "")
                val username = prefManager.getString("webdav_username", "")
                val password = prefManager.getString("webdav_password", "")
                
                if (url.isNullOrEmpty() || username.isNullOrEmpty() || password.isNullOrEmpty()) {
                    showToast("Please fill in all WebDAV settings")
                    return@launch
                }
                
                // Show progress
                showToast("Testing WebDAV connection...")
                
                // Test connection in background
                withContext(Dispatchers.IO) {
                    val sardine = com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine()
                    sardine.setCredentials(username, password)
                    
                    // Try to list root directory
                    try {
                        val resources = sardine.list(url)
                        
                        // Show success dialog with details
                        withContext(Dispatchers.Main) {
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("✓ WebDAV Connection Successful")
                                .setMessage(buildString {
                                    append("Server: $url\n\n")
                                    append("Found ${resources.size} items:\n\n")
                                    resources.take(10).forEach { resource ->
                                        val icon = if (resource.isDirectory) "📁" else "📄"
                                        append("$icon ${resource.name}\n")
                                    }
                                    if (resources.size > 10) {
                                        append("... and ${resources.size - 10} more")
                                    }
                                })
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        
                        Logger.i(TAG, "WebDAV test successful: $url")
                        
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("❌ WebDAV Connection Failed")
                                .setMessage(buildString {
                                    append("Could not connect to WebDAV server\n\n")
                                    append("Error: ${e.message}\n\n")
                                    append("Please check:\n")
                                    append("• URL is correct and accessible\n")
                                    append("• Username and password are valid\n")
                                    append("• Server supports WebDAV\n")
                                    append("• Firewall is not blocking connection")
                                })
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        
                        Logger.e(TAG, "WebDAV test failed", e)
                    }
                }
                
            } catch (e: Exception) {
                Logger.e(TAG, "WebDAV test failed", e)
                showError("WebDAV test failed: ${e.message}", "Error")
            }
        }
    }
    
    private fun showToast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Setup sync enable/disable toggle
     */
    private fun setupSyncToggle() {
        findPreference<SwitchPreferenceCompat>("sync_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean

                if (enabled) {
                    val backend = prefManager.getSyncBackend()

                    // Detect actual backend for "auto" mode
                    val hasGooglePlay = try {
                        requireContext().packageManager.getPackageInfo("com.google.android.gms", 0)
                        true
                    } catch (e: Exception) {
                        false
                    }

                    val actualBackend = if (backend == "auto") {
                        if (hasGooglePlay) "google_drive" else "webdav"
                    } else {
                        backend
                    }

                    val hasPassword = androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(requireContext())
                        .getBoolean("sync_password_set", false)

                    when (actualBackend) {
                        "google_drive" -> {
                            // Google Drive requires authentication and password
                            if (!syncManager.getAuthManager().isAuthenticated()) {
                                showSyncSetupError("Google account not connected", "Please tap 'Google account' above to sign in first.")
                                false
                            } else if (!hasPassword) {
                                showSyncSetupError("Encryption password not set", "Please tap 'Sync encryption password' below to set a password.")
                                false
                            } else {
                                enableSync()
                                updateSyncStatus()
                                true
                            }
                        }
                        "webdav" -> {
                            // WebDAV requires server settings and password
                            val url = prefManager.getWebDAVServerUrl()
                            val username = prefManager.getWebDAVUsername()
                            val password = prefManager.getWebDAVPassword()

                            if (url.isNullOrEmpty() || username.isNullOrEmpty() || password.isNullOrEmpty()) {
                                showSyncSetupError("WebDAV not configured", "Please fill in the WebDAV settings above:\n• Server URL\n• Username\n• Password")
                                false
                            } else if (!hasPassword) {
                                showSyncSetupError("Encryption password not set", "Please tap 'Sync encryption password' below to set a password.")
                                false
                            } else {
                                enableSync()
                                updateSyncStatus()
                                true
                            }
                        }
                        else -> {
                            showToast("Unknown sync backend: $backend")
                            false
                        }
                    }
                } else {
                    disableSync()
                    updateSyncStatus()
                    true
                }
            }
        }
    }

    /**
     * Show sync setup error dialog with clear instructions
     */
    private fun showSyncSetupError(title: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Cannot Enable Sync")
            .setMessage("$title\n\n$message")
            .setPositiveButton("OK", null)
            .setNeutralButton("Show Setup") { _, _ ->
                showSyncSetupDialog()
            }
            .show()
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
        try {
            val signInIntent = syncManager.getAuthManager().getSignInIntent()
            if (signInIntent != null) {
                signInLauncher.launch(signInIntent)
                Logger.d(TAG, "Launched Google Sign-In intent")
            } else {
                showSignInError("Failed to create sign-in intent. Please check Google Play Services.")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start sign-in", e)
            showSignInError("Failed to start sign-in: ${e.message}")
        }
    }

    /**
     * Show sign-in error dialog
     */
    private fun showSignInError(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Sign-In Failed")
            .setMessage("$message\n\nPlease ensure:\n• Google Play Services is installed and updated\n• You have an active internet connection\n• Your Google account is properly configured")
            .setPositiveButton("OK", null)
            .setNeutralButton("Retry") { _, _ ->
                signIn()
            }
            .show()
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

        val backend = prefManager.getSyncBackend()

        when (backend) {
            "google_drive" -> {
                // Launch Google Sign-In flow using Activity Result API
                signIn()
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

        // Create error text view for inline error messages
        val errorText = android.widget.TextView(requireContext()).apply {
            setTextColor(android.graphics.Color.RED)
            setPadding(0, 8, 0, 0)
            visibility = android.view.View.GONE
        }

        val editText = android.widget.EditText(requireContext()).apply {
            hint = "Enter encryption password (min 8 chars)"
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
            addView(errorText)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Set Encryption Password")
            .setMessage("This password will be used to encrypt your synced data. Keep it safe!")
            .setView(layout)
            .setPositiveButton("Set Password", null)  // Set null to override default dismiss behavior
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Override positive button to prevent auto-dismiss on validation error
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = editText.text.toString()
            val confirm = confirmEditText.text.toString()

            // Validate and show inline errors
            when {
                password.isBlank() -> {
                    errorText.text = "✗ Password cannot be empty"
                    errorText.visibility = android.view.View.VISIBLE
                    editText.requestFocus()
                }
                password.length < 8 -> {
                    errorText.text = "✗ Password must be at least 8 characters (currently ${password.length})"
                    errorText.visibility = android.view.View.VISIBLE
                    editText.requestFocus()
                }
                password != confirm -> {
                    errorText.text = "✗ Passwords do not match"
                    errorText.visibility = android.view.View.VISIBLE
                    confirmEditText.requestFocus()
                }
                else -> {
                    // Save password and dismiss dialog
                    syncManager.setSyncPassword(password)
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putBoolean("sync_password_set", true)
                        .apply()

                    android.widget.Toast.makeText(requireContext(), "✓ Encryption password set successfully", android.widget.Toast.LENGTH_SHORT).show()
                    findPreference<Preference>("sync_password")?.summary = "Password configured (tap to change)"
                    updateSyncStatus()  // Update status after password set
                    Logger.i(TAG, "Sync encryption password set")
                    dialog.dismiss()
                }
            }
        }
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
                                Backend: ${prefManager.getSyncBackend()}
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

    // Note: onActivityResult removed - using Activity Result API (signInLauncher) instead
}
