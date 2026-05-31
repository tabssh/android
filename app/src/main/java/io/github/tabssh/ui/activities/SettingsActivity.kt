package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ui.activities.SyncSettingsActivity
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity :
    AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Setup toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsMainFragment())
                .commit()
        }

        Logger.d("SettingsActivity", "onCreate completed")
    }

    /**
     * gh #3 fix — instantiate and navigate to the preference's
     * `android:fragment="..."` target. Without this callback, AndroidX
     * Preference throws when the user taps any list entry that uses the
     * `fragment` attribute, which is what made every settings tap crash
     * back to the main screen.
     */
    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val fragmentName = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            fragmentName
        ).apply {
            arguments = pref.extras
        }
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
        supportActionBar?.title = pref.title
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            // Restore main title when popping back to the root list.
            if (supportFragmentManager.backStackEntryCount == 1) {
                supportActionBar?.title = "Settings"
            }
            return true
        }
        finish()
        return true
    }
}

class SettingsMainFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey)

        findPreference<Preference>("sync_settings")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), SyncSettingsActivity::class.java))
            true
        }

        findPreference<Preference>("import_export")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ImportExportActivity::class.java))
            true
        }

        findPreference<Preference>("about_version")?.apply {
            try {
                val versionName = io.github.tabssh.BuildConfig.VERSION_NAME ?: "1.0.0"
                val versionCode = io.github.tabssh.BuildConfig.VERSION_CODE
                summary = "$versionName ($versionCode)"
            } catch (e: Exception) {
                Logger.e("SettingsMainFragment", "Failed to load version info", e)
                summary = "1.0.0 (1)"
            }
        }

        findPreference<Preference>("about_build")?.apply {
            try {
                val commitId = io.github.tabssh.BuildConfig.GIT_COMMIT_ID ?: "unknown"
                val buildDate = io.github.tabssh.BuildConfig.BUILD_DATE ?: "unknown"
                val buildType = io.github.tabssh.BuildConfig.BUILD_TYPE ?: "debug"
                val buildInfo = """
                    Commit: $commitId
                    Built: $buildDate
                    Type: $buildType
                """.trimIndent()
                summary = buildInfo
            } catch (e: Exception) {
                Logger.e("SettingsMainFragment", "Failed to load build info", e)
                summary = "Build information unavailable"
            }
        }
    }
}

class GeneralSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_general, rootKey)

        findPreference<Preference>("app_language")?.setOnPreferenceChangeListener { _, newValue ->
            val lang = newValue as String
            val localeList = if (lang.isBlank() || lang == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(lang)
            }
            AppCompatDelegate.setApplicationLocales(localeList)
            true
        }

        findPreference<Preference>("app_theme")?.setOnPreferenceChangeListener { _, newValue ->
            val theme = newValue as String
            when (theme) {
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
            true
        }

        // Open Theme Editor
        findPreference<Preference>("open_theme_editor")?.setOnPreferenceClickListener {
            startActivity(ThemeEditorActivity.createIntent(requireContext()))
            true
        }

        // Open system notification settings
        findPreference<Preference>("open_system_notification_settings")?.setOnPreferenceClickListener {
            openSystemNotificationSettings()
            true
        }
    }

    private fun openSystemNotificationSettings() {
        try {
            val intent = Intent().apply {
                when {
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O -> {
                        action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                    }
                    else -> {
                        action = "android.settings.APP_NOTIFICATION_SETTINGS"
                        putExtra("app_package", requireContext().packageName)
                        putExtra("app_uid", requireContext().applicationInfo.uid)
                    }
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to app details settings
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
            } catch (e2: Exception) {
                android.widget.Toast.makeText(requireContext(), "Unable to open notification settings", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class SecuritySettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_security, rootKey)

        // Clear known hosts functionality
        findPreference<Preference>("clear_known_hosts")?.setOnPreferenceClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear Known Hosts")
                .setMessage("This will remove all saved host keys and fingerprints. You will need to verify hosts again on next connection.\n\nContinue?")
                .setPositiveButton("Clear") { _, _ ->
                    clearKnownHosts()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        // Security lock - enable/disable biometric based on lock state
        findPreference<SwitchPreferenceCompat>("security_lock_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                android.widget.Toast.makeText(requireContext(), "Security lock enabled", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(requireContext(), "Security lock disabled", android.widget.Toast.LENGTH_SHORT).show()
            }
            true
        }

        // Biometric authentication
        findPreference<SwitchPreferenceCompat>("biometric_auth")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                // Check if biometric is available
                val biometricManager = androidx.biometric.BiometricManager.from(requireContext())
                when (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                    androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> {
                        android.widget.Toast.makeText(requireContext(), "Biometric authentication enabled", android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                        android.widget.Toast.makeText(requireContext(), "No biometric hardware available", android.widget.Toast.LENGTH_SHORT).show()
                        false
                    }
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                        android.widget.Toast.makeText(requireContext(), "Biometric hardware unavailable", android.widget.Toast.LENGTH_SHORT).show()
                        false
                    }
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                        android.widget.Toast.makeText(requireContext(), "No biometric enrolled. Please add fingerprint/face in device settings", android.widget.Toast.LENGTH_LONG).show()
                        false
                    }
                    else -> {
                        android.widget.Toast.makeText(requireContext(), "Biometric authentication not available", android.widget.Toast.LENGTH_SHORT).show()
                        false
                    }
                }
            } else {
                android.widget.Toast.makeText(requireContext(), "Biometric authentication disabled", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }

        // Lock timeout
        findPreference<Preference>("security_lock_timeout")?.setOnPreferenceChangeListener { _, newValue ->
            val timeout = newValue as String
            val minutes = timeout.toInt() / 60
            android.widget.Toast.makeText(requireContext(), "Lock timeout set to $minutes minute(s)", android.widget.Toast.LENGTH_SHORT).show()
            true
        }

        // Wave 8.3 — FIDO2 hardware detection (alpha, untested for SSH auth)
        findPreference<Preference>("fido2_detector")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            val results = io.github.tabssh.crypto.fido.Fido2Detector.detect(ctx)
            val message = if (results.isEmpty()) {
                "No FIDO2 / U2F authenticators detected.\n\n" +
                "If you have a YubiKey or similar, plug it into a USB-OTG adapter or " +
                "tap it to the back of the phone (NFC). Then re-open this dialog.\n\n" +
                "Note: SSH auth via these keys (sk-ed25519, sk-ecdsa) is NOT yet " +
                "wired in TabSSH — JSch lacks `sk-*` key support. This dialog only " +
                "confirms the device sees the hardware."
            } else {
                buildString {
                    appendLine("Detected ${results.size} FIDO2-capable device(s):")
                    appendLine()
                    results.forEach { appendLine("• ${it.summary()}") }
                    appendLine()
                    append("⚠️ Alpha — TabSSH does NOT yet sign SSH challenges with these keys.")
                }
            }
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("FIDO2 Hardware (Alpha)")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        // Wave 3.2 — PIN lock setup / change / disable
        findPreference<Preference>("app_lock_pin_setup")?.setOnPreferenceClickListener {
            val app = requireActivity().application as TabSSHApplication
            val enabled = app.preferencesManager.getBoolean(io.github.tabssh.ui.activities.PinLockActivity.PREF_PIN_ENABLED, false)
            val ctx = requireContext()
            val items = if (enabled) arrayOf("Change PIN", "Disable PIN lock") else arrayOf("Set PIN")
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("App lock PIN")
                .setItems(items) { _, which ->
                    when {
                        items[which] == "Set PIN" || items[which] == "Change PIN" -> {
                            startActivity(io.github.tabssh.ui.activities.PinLockActivity.setupIntent(ctx))
                        }
                        items[which] == "Disable PIN lock" -> {
                            app.preferencesManager.setBoolean(io.github.tabssh.ui.activities.PinLockActivity.PREF_PIN_ENABLED, false)
                            app.preferencesManager.remove(io.github.tabssh.ui.activities.PinLockActivity.PREF_PIN_HASH)
                            android.widget.Toast.makeText(ctx, "PIN lock disabled", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private fun clearKnownHosts() {
        try {
            val app = requireActivity().application as? TabSSHApplication
            if (app == null) {
                Logger.e("Settings", "Failed to get TabSSHApplication instance")
                android.widget.Toast.makeText(requireContext(), "Error: Application not available", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            
            // Clear host keys from database
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        app.database.hostKeyDao().deleteAllHostKeys()
                    }
                    android.widget.Toast.makeText(requireContext(), "Known hosts cleared", android.widget.Toast.LENGTH_SHORT).show()
                    Logger.i("Settings", "Cleared all known hosts")
                } catch (e: Exception) {
                    Logger.e("Settings", "Database error while clearing known hosts", e)
                    android.widget.Toast.makeText(requireContext(), "Error clearing known hosts: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Logger.e("Settings", "Failed to clear known hosts", e)
            android.widget.Toast.makeText(requireContext(), "Error clearing known hosts", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

class TerminalSettingsFragment : PreferenceFragmentCompat() {

    private val themeImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importThemeFromUri(it) }
    }

    private val themeExportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportThemeToUri(it) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_terminal, rootKey)

        // Terminal theme change listener
        findPreference<Preference>("terminal_theme")?.setOnPreferenceChangeListener { _, newValue ->
            val themeName = newValue as String
            android.widget.Toast.makeText(requireContext(), "Terminal theme changed to $themeName", android.widget.Toast.LENGTH_SHORT).show()
            Logger.i("Settings", "Terminal theme changed to: $themeName")
            true
        }

        // Terminal font change listener
        findPreference<Preference>("terminal_font")?.setOnPreferenceChangeListener { _, newValue ->
            val fontName = newValue as String
            android.widget.Toast.makeText(requireContext(), "Terminal font changed to $fontName", android.widget.Toast.LENGTH_SHORT).show()
            Logger.i("Settings", "Terminal font changed to: $fontName")
            true
        }

        // Font size change listener
        findPreference<Preference>("terminal_font_size")?.setOnPreferenceChangeListener { _, newValue ->
            val size = newValue as Int
            android.widget.Toast.makeText(requireContext(), "Font size: ${size}sp", android.widget.Toast.LENGTH_SHORT).show()
            Logger.i("Settings", "Terminal font size changed to: $size")
            true
        }

        // Cursor style change listener
        findPreference<Preference>("terminal_cursor_style")?.setOnPreferenceChangeListener { _, newValue ->
            val style = newValue as String
            android.widget.Toast.makeText(requireContext(), "Cursor style: $style", android.widget.Toast.LENGTH_SHORT).show()
            Logger.i("Settings", "Cursor style changed to: $style")
            true
        }

        // Scrollback buffer change listener
        findPreference<Preference>("terminal_scrollback")?.setOnPreferenceChangeListener { _, newValue ->
            val lines = newValue as String
            try {
                val numLines = lines.toInt()
                // -1 = unlimited, minimum 250 lines
                if (numLines != -1 && numLines < 250) {
                    android.widget.Toast.makeText(requireContext(), "Scrollback minimum is 250 lines (or -1 for unlimited)", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceChangeListener false
                }
                if (numLines > 100000 && numLines != -1) {
                    android.widget.Toast.makeText(requireContext(), "Scrollback maximum is 100,000 lines", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceChangeListener false
                }
                val message = if (numLines == -1) "Scrollback: Unlimited" else "Scrollback: $numLines lines"
                android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
                Logger.i("Settings", "Scrollback buffer changed to: $numLines")
                true
            } catch (e: NumberFormatException) {
                android.widget.Toast.makeText(requireContext(), "Invalid number", android.widget.Toast.LENGTH_SHORT).show()
                false
            }
        }

        // Multiplexer type change listener - update prefix to saved value for type
        val app = requireActivity().application as io.github.tabssh.TabSSHApplication
        val customPrefixPref = findPreference<androidx.preference.EditTextPreference>("multiplexer_custom_prefix")

        findPreference<androidx.preference.ListPreference>("gesture_multiplexer_type")?.setOnPreferenceChangeListener { _, newValue ->
            val type = newValue as String
            // Load saved prefix for this type
            val savedPrefix = app.preferencesManager.getMultiplexerPrefix(type)
            customPrefixPref?.text = savedPrefix
            customPrefixPref?.summary = "Current: $savedPrefix (leave empty for default)"
            Logger.i("Settings", "Multiplexer type changed to: $type (prefix: $savedPrefix)")
            true
        }

        // Custom prefix change listener - save to current multiplexer type
        customPrefixPref?.setOnPreferenceChangeListener { _, newValue ->
            val prefix = newValue as String
            val currentType = findPreference<androidx.preference.ListPreference>("gesture_multiplexer_type")?.value ?: "tmux"
            app.preferencesManager.setMultiplexerPrefix(currentType, prefix)
            Logger.i("Settings", "Multiplexer prefix for $currentType changed to: $prefix")
            true
        }

        // Initialize prefix summary with current value
        val currentType = findPreference<androidx.preference.ListPreference>("gesture_multiplexer_type")?.value ?: "tmux"
        val currentPrefix = app.preferencesManager.getMultiplexerPrefix(currentType)
        customPrefixPref?.text = currentPrefix
        customPrefixPref?.summary = "Current: $currentPrefix (leave empty for default)"

        // Import custom theme click listener
        findPreference<Preference>("import_custom_theme")?.setOnPreferenceClickListener {
            themeImportLauncher.launch("application/json")
            true
        }

        // Export current theme click listener
        findPreference<Preference>("export_current_theme")?.setOnPreferenceClickListener {
            val themeName = app.preferencesManager.getString("terminal_theme", "custom")
            themeExportLauncher.launch("$themeName.json")
            true
        }

        // Keyboard customization click listener
        findPreference<Preference>("customize_keyboard_layout")?.setOnPreferenceClickListener {
            val intent = android.content.Intent(requireContext(), KeyboardCustomizationActivity::class.java)
            startActivity(intent)
            true
        }

        // Reset keyboard layout — wipes the saved JSON so the next
        // terminal launch falls back to the built-in default. Confirm
        // first since it's destructive (user could have spent time
        // arranging keys).
        findPreference<Preference>("reset_keyboard_layout")?.setOnPreferenceClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Reset keyboard layout?")
                .setMessage("This restores the default 3-row layout (Esc/Tab/Ctl/Alt/Fn/Enter, arrows + Home/End/PgUp/PgDn, common symbols). Your current layout will be discarded.")
                .setPositiveButton("Reset") { _, _ ->
                    app.preferencesManager.setKeyboardLayoutJson(null)
                    // Clear the customised flag so future default-layout updates
                    // propagate automatically — the user chose the default layout.
                    app.preferencesManager.setKeyboardLayoutCustomized(false)
                    app.preferencesManager.setKeyboardLayoutVersion(
                        io.github.tabssh.ui.keyboard.MultiRowKeyboardView.CURRENT_DEFAULT_LAYOUT_VERSION
                    )
                    Toast.makeText(requireContext(), "Keyboard layout reset", Toast.LENGTH_SHORT).show()
                    Logger.i("Settings", "Keyboard layout reset to default")
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private fun importThemeFromUri(uri: android.net.Uri) {
        val app = requireActivity().application as TabSSHApplication
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val json = requireContext().contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (json.isNullOrBlank()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Empty or unreadable theme file", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val result = app.themeManager.importTheme(json)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    when (result) {
                        is io.github.tabssh.themes.definitions.ImportThemeResult.Success ->
                            Toast.makeText(
                                requireContext(),
                                "Theme \"${result.theme.name}\" imported",
                                Toast.LENGTH_SHORT
                            ).show()
                        is io.github.tabssh.themes.definitions.ImportThemeResult.Error ->
                            Toast.makeText(requireContext(), "Import failed: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Logger.e("Settings", "Theme import failed", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportThemeToUri(uri: android.net.Uri) {
        val app = requireActivity().application as TabSSHApplication
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val themeId = app.preferencesManager.getString("terminal_theme", "dark")
                val json = app.themeManager.exportTheme(themeId)
                if (json == null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Theme not found", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(json)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Theme exported", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Logger.e("Settings", "Theme export failed", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

class ConnectionSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_connection, rootKey)

        // Default username change listener
        findPreference<Preference>("default_username")?.setOnPreferenceChangeListener { _, newValue ->
            val username = newValue as String
            if (username.isBlank()) {
                android.widget.Toast.makeText(requireContext(), "Username cannot be empty", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnPreferenceChangeListener false
            }
            android.widget.Toast.makeText(requireContext(), "Default username: $username", android.widget.Toast.LENGTH_SHORT).show()
            Logger.i("Settings", "Default username changed to: $username")
            true
        }

        // Default port change listener
        findPreference<Preference>("default_port")?.setOnPreferenceChangeListener { _, newValue ->
            val port = newValue as String
            try {
                val portNum = port.toInt()
                if (portNum < 1 || portNum > 65535) {
                    android.widget.Toast.makeText(requireContext(), "Port must be between 1-65535", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceChangeListener false
                }
                android.widget.Toast.makeText(requireContext(), "Default port: $portNum", android.widget.Toast.LENGTH_SHORT).show()
                Logger.i("Settings", "Default port changed to: $portNum")
                true
            } catch (e: NumberFormatException) {
                android.widget.Toast.makeText(requireContext(), "Invalid port number", android.widget.Toast.LENGTH_SHORT).show()
                false
            }
        }

        // Connection timeout change listener
        findPreference<Preference>("connection_timeout")?.setOnPreferenceChangeListener { _, newValue ->
            val timeout = newValue as String
            val seconds = timeout.toInt()
            android.widget.Toast.makeText(requireContext(), "Connection timeout: ${seconds}s", android.widget.Toast.LENGTH_SHORT).show()
            Logger.i("Settings", "Connection timeout changed to: $seconds")
            true
        }

        // Keep-alive listener removed — the toggle and interval preferences
        // are gone from preferences_connection.xml. SSH-layer keepalive is
        // unconditionally on at the mobile-default 10s interval (see
        // SSHConnection.configureSession).
    }
}

/**
 * Audit Log Settings Fragment
 */
class AuditSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_audit, rootKey)

        val app = requireActivity().application as TabSSHApplication

        // MDM status banner — show and lock the toggle when an EMM policy is active.
        if (app.auditLogManager.isMdmManaged()) {
            findPreference<Preference>("audit_mdm_status")?.isVisible = true
            findPreference<androidx.preference.SwitchPreferenceCompat>("audit_log_enabled")?.apply {
                isEnabled = false
                // Reflect the MDM-forced value so the toggle shows the right state.
                isChecked = app.auditLogManager.isEnabled()
            }
        }

        // View logs button
        findPreference<Preference>("audit_log_viewer")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AuditLogViewerActivity::class.java))
            true
        }
        
        // Export logs button
        findPreference<Preference>("audit_log_export")?.setOnPreferenceClickListener {
            exportAuditLogs()
            true
        }
        
        // Clear logs button
        findPreference<Preference>("audit_log_clear")?.setOnPreferenceClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear Audit Logs")
                .setMessage("Delete all audit log entries? This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            app.auditLogManager.deleteAllLogs()
                        }
                        android.widget.Toast.makeText(requireContext(), "Audit logs cleared", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        
        Logger.d("AuditSettingsFragment", "Settings view created")
    }
    
    private fun exportAuditLogs() {
        lifecycleScope.launch {
            try {
                val app = requireActivity().application as TabSSHApplication
                val logs = withContext(Dispatchers.IO) {
                    app.database.auditLogDao().getRecent(1000) // Get last 1000 logs
                }

                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val filename = "audit_logs_$timestamp.csv"

                val file = java.io.File(requireContext().getExternalFilesDir(null), filename)
                withContext(Dispatchers.IO) {
                    file.writeText(buildString {
                        append("Timestamp,Connection,Session,EventType,Command,Output\n")
                        logs.forEach { log ->
                            append("${log.timestamp},${log.connectionId},${log.sessionId},")
                            append("${log.eventType},\"${log.command ?: ""}\",")
                            append("\"${log.output ?: ""}\"\n")
                        }
                    })
                }
                
                android.widget.Toast.makeText(
                    requireContext(),
                    "✓ Exported ${logs.size} logs to $filename",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Failed to export: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

class TaskerSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_tasker, rootKey)
        
        // Actions help
        findPreference<Preference>("tasker_actions_help")?.setOnPreferenceClickListener {
            showActionsHelp()
            true
        }
        
        // Events help
        findPreference<Preference>("tasker_events_help")?.setOnPreferenceClickListener {
            showEventsHelp()
            true
        }
        
        // Load connection list for allowed connections
        val app = requireActivity().application as TabSSHApplication
        lifecycleScope.launch {
            app.database.connectionDao().getAllConnections()
                .flowOn(Dispatchers.IO)
                .collect { connections ->
                    val entries = connections.map { it.name }.toTypedArray()
                    val values = connections.map { it.id.toString() }.toTypedArray()

                    findPreference<androidx.preference.MultiSelectListPreference>("tasker_allowed_connections")?.apply {
                        this.entries = entries
                        this.entryValues = values
                    }
                }
        }
    }
    
    private fun showActionsHelp() {
        val message = """
            Available Tasker Actions:
            
            📱 CONNECT
            Connect to SSH server
            Extras: connection_id or connection_name
            
            📴 DISCONNECT
            Disconnect from server
            Extras: connection_id or connection_name
            
            ⌨️ SEND_COMMAND
            Execute command
            Extras: connection_id/name, command, wait_for_result, timeout_ms
            
            🔑 SEND_KEYS
            Send key sequence
            Extras: connection_id/name, keys
            
            Examples:
            - Keys: "Enter", "Tab", "Ctrl+C"
            - Command: "ls -la" with wait_for_result=true
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Tasker Actions")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showEventsHelp() {
        val message = """
            Broadcast Events:
            
            ✅ CONNECTED
            Broadcast when connection established
            Extras: connection_id, connection_name
            
            ❌ DISCONNECTED
            Broadcast when connection closed
            Extras: connection_id, connection_name
            
            📊 COMMAND_RESULT
            Broadcast command output
            Extras: connection_id, connection_name, command, result
            
            ⚠️ ERROR
            Broadcast when error occurs
            Extras: error
            
            Use these in Tasker with Event → Intent Received
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Tasker Events")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}

/**
 * Logging settings fragment
 */
class LoggingSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_logging, rootKey)

        // Debug Logging category is developer-only. Hide it entirely in release
        // builds so production users never see it.
        if (!io.github.tabssh.BuildConfig.DEBUG_MODE) {
            findPreference<androidx.preference.PreferenceCategory>("debug_logging_category")?.isVisible = false
        }

        // Live-toggle the Logger when the user flips the master switch.
        // Without this, the pref persisted but `Logger.logToFile` /
        // `Logger.logFile` only updated on the next cold start. Now flipping
        // the switch creates the log file immediately (or stops writing
        // to it when turned off).
        findPreference<androidx.preference.SwitchPreferenceCompat>("debug_logging_enabled")
            ?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: false
                val app = requireActivity().application as io.github.tabssh.TabSSHApplication
                if (enabled) {
                    io.github.tabssh.utils.logging.Logger.forceEnableDebugMode(requireContext())
                    app.startAnrWatchdog()
                } else {
                    io.github.tabssh.utils.logging.Logger.disableDebugMode()
                    app.stopAnrWatchdog()
                }
                true
            }

        // Live-toggle keystroke-byte logging. Default off for privacy
        // (every byte typed at the terminal — including remote sudo/ssh
        // password prompts — flows through TermuxBridge). Static flag so
        // the bridge doesn't need to know about Context.
        findPreference<androidx.preference.SwitchPreferenceCompat>("log_keystroke_bytes")
            ?.setOnPreferenceChangeListener { _, newValue ->
                io.github.tabssh.terminal.TermuxBridge.logKeystrokeBytes = newValue as? Boolean ?: false
                true
            }
        // Apply persisted value on fragment open (covers cold start before
        // the user touches the toggle).
        io.github.tabssh.terminal.TermuxBridge.logKeystrokeBytes =
            (requireActivity().application as io.github.tabssh.TabSSHApplication)
                .preferencesManager.getBoolean("log_keystroke_bytes", false)

        // View Debug Log
        findPreference<Preference>("view_debug_log")?.setOnPreferenceClickListener {
            showLogViewer("Debug Log", "debug")
            true
        }

        // View Host Logs
        findPreference<Preference>("view_host_logs")?.setOnPreferenceClickListener {
            showHostLogsSelector()
            true
        }

        // View Audit Log — open the dedicated viewer (audit log lives in Room,
        // not a flat file, so we hand off to AuditLogViewerActivity).
        findPreference<Preference>("view_audit_log")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AuditLogViewerActivity::class.java))
            true
        }

        // View Application Log
        findPreference<Preference>("view_app_log")?.setOnPreferenceClickListener {
            showLogViewer("Application Log", "app")
            true
        }

        // Export logs
        findPreference<Preference>("export_logs")?.setOnPreferenceClickListener {
            exportLogs()
            true
        }

        // Clear logs
        findPreference<Preference>("clear_logs")?.setOnPreferenceClickListener {
            clearLogs()
            true
        }

        // Test crash — debug builds only.  Throws a RuntimeException on the
        // main thread so the UncaughtExceptionHandler fires and the crash
        // dialog appears, letting developers verify crash reporting end-to-end.
        val testCrashPref = findPreference<androidx.preference.Preference>("test_crash")
        if (io.github.tabssh.BuildConfig.DEBUG_MODE) {
            testCrashPref?.setOnPreferenceClickListener {
                throw RuntimeException(
                    "Test crash — verify crash dialog\n" +
                    "Sensitive data that must be redacted: host=192.168.1.10 " +
                    "user=admin password=hunter2 token=abc123secret"
                )
                @Suppress("UNREACHABLE_CODE")
                true
            }
        } else {
            testCrashPref?.isVisible = false
        }
    }

    private fun showLogViewer(title: String, logType: String) {
        lifecycleScope.launch {
            try {
                val logContent = when (logType) {
                    "debug" -> io.github.tabssh.utils.logging.Logger.getDebugLogs()
                    "app" -> io.github.tabssh.utils.logging.Logger.getRecentLogs()
                        .joinToString("\n") { "${it.timestamp} [${it.level}] ${it.tag}: ${it.message}" }
                    else -> "No logs available"
                }

                val displayContent = if (logContent.isBlank()) "No logs found" else logContent

                // Create scrollable text view
                val scrollView = android.widget.ScrollView(requireContext())
                val textView = android.widget.TextView(requireContext()).apply {
                    text = displayContent
                    setPadding(32, 16, 32, 16)
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                }
                scrollView.addView(textView)

                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setView(scrollView)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Copy") { _, _ ->
                        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText(title, displayContent)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(requireContext(), "Log copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Paste / Issue") { _, _ ->
                        io.github.tabssh.ui.dialogs.ReportIssueDialog
                            .create(displayContent, logType)
                            .show(parentFragmentManager, "report_issue")
                    }
                    .show()

            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), "Error loading logs: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showHostLogsSelector() {
        lifecycleScope.launch {
            try {
                val hostLogs = io.github.tabssh.utils.logging.Logger.getHostLogFiles()

                if (hostLogs.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "No host logs found", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val hostNames = hostLogs.map { it.name }.toTypedArray()

                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Select Host Log")
                    .setItems(hostNames) { _, which ->
                        showHostLogContent(hostLogs[which])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), "Error loading host logs: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showHostLogContent(logFile: java.io.File) {
        lifecycleScope.launch {
            try {
                val content = logFile.readText()
                val displayContent = if (content.isBlank()) "No content" else content

                val scrollView = android.widget.ScrollView(requireContext())
                val textView = android.widget.TextView(requireContext()).apply {
                    text = displayContent
                    setPadding(32, 16, 32, 16)
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                }
                scrollView.addView(textView)

                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(logFile.name)
                    .setView(scrollView)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Copy") { _, _ ->
                        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText(logFile.name, displayContent)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(requireContext(), "Log copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .show()

            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), "Error reading log: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun exportLogs() {
        lifecycleScope.launch {
            try {
                val logs = io.github.tabssh.utils.logging.Logger.getRecentLogs()
                
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val filename = "tabssh_logs_$timestamp.txt"
                
                val file = java.io.File(requireContext().getExternalFilesDir(null), filename)
                file.writeText(buildString {
                    append("TabSSH Application Logs\n")
                    append("Exported: $timestamp\n")
                    append("=".repeat(80) + "\n\n")
                    
                    logs.forEach { log ->
                        append("${log.timestamp} [${log.level}] ${log.tag}: ${log.message}\n")
                    }
                })
                
                android.widget.Toast.makeText(
                    requireContext(),
                    "✓ Exported ${logs.size} log entries to $filename",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Failed to export: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun clearLogs() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Logs")
            .setMessage("Are you sure you want to delete all log files? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                io.github.tabssh.utils.logging.Logger.clearLogs()
                android.widget.Toast.makeText(
                    requireContext(),
                    "✓ All logs cleared",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/**
 * Monitoring settings screen.
 *
 * Covers the background host-availability worker: the global on/off toggle,
 * battery optimization exemption status (updated live in onResume so the user
 * immediately sees the result of tapping through to the system prompt), OEM
 * battery settings shortcut, default performance thresholds, and notification
 * channel access.
 */
class MonitoringSettingsFragment : androidx.preference.PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: android.os.Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_monitoring, rootKey)

        // Embed the selected entry label into the cooldown summary sentence so
        // the user sees e.g. "1 hour between repeated 'still down' notifications"
        // rather than a bare value or a literal "%s".
        findPreference<androidx.preference.ListPreference>("monitoring_alert_cooldown_minutes")
            ?.summaryProvider = androidx.preference.Preference.SummaryProvider<androidx.preference.ListPreference> { pref ->
                val entry = pref.entry
                if (entry.isNullOrEmpty()) "Not set"
                else "$entry between repeated 'still down' notifications"
            }

        // Master toggle — schedule or cancel the background worker.
        findPreference<androidx.preference.SwitchPreferenceCompat>("monitoring_enabled")
            ?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: false
                val ctx = requireContext()
                if (enabled) {
                    io.github.tabssh.background.HostAvailabilityWorker.schedule(ctx)
                    // Prompt for battery optimization if not already exempt.
                    io.github.tabssh.background.BatteryOptimizationHelper
                        .requestExemptionIfNeeded(ctx) {
                            io.github.tabssh.background.BatteryOptimizationHelper
                                .showManufacturerGuidanceIfNeeded(ctx)
                        }
                } else {
                    io.github.tabssh.background.HostAvailabilityWorker.cancel(ctx)
                }
                // Persist immediately so the BootReceiver and Application.onCreate
                // can read the same key on cold start.
                androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(ctx)
                    .edit()
                    .putBoolean("monitoring_enabled", enabled)
                    .apply()
                true
            }

        // Battery optimization action — opens the system exemption prompt.
        findPreference<androidx.preference.Preference>("monitoring_battery_status")
            ?.setOnPreferenceClickListener {
                val ctx = requireContext()
                if (io.github.tabssh.background.BatteryOptimizationHelper.isExempt(ctx)) {
                    android.widget.Toast.makeText(
                        ctx,
                        "Battery optimization is already disabled for TabSSH.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    io.github.tabssh.background.BatteryOptimizationHelper
                        .requestExemptionIfNeeded(ctx)
                }
                true
            }

        // OEM battery settings shortcut.
        findPreference<androidx.preference.Preference>("monitoring_oem_battery")
            ?.setOnPreferenceClickListener {
                io.github.tabssh.background.BatteryOptimizationHelper
                    .showManufacturerGuidanceIfNeeded(requireContext())
                true
            }

        // Notification channel deep-link (API 26+).
        findPreference<androidx.preference.Preference>("monitoring_open_notification_channel")
            ?.setOnPreferenceClickListener {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                    ).apply {
                        putExtra(
                            android.provider.Settings.EXTRA_APP_PACKAGE,
                            requireContext().packageName
                        )
                        putExtra(
                            android.provider.Settings.EXTRA_CHANNEL_ID,
                            io.github.tabssh.utils.NotificationHelper.CHANNEL_HOST_MONITORING
                        )
                    }
                    try {
                        startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Cannot open notification settings on this device.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Notification channels require Android 8.0+.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the battery status summary whenever the fragment becomes
        // visible — covers the case where the user taps through to the system
        // prompt, grants the exemption, and presses Back.
        updateBatteryStatusSummary()
    }

    private fun updateBatteryStatusSummary() {
        val ctx = requireContext()
        val exempt = io.github.tabssh.background.BatteryOptimizationHelper.isExempt(ctx)
        findPreference<androidx.preference.Preference>("monitoring_battery_status")?.apply {
            summary = if (exempt) {
                "Exempt — background monitoring will run reliably"
            } else {
                "Not exempt — tap to disable battery optimization for reliable alerts"
            }
        }
    }
}

