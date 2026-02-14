package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
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

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        finish()
        return true
    }
}

class SettingsMainFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey)

        // Set version information from BuildConfig with fallback values
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

        findPreference<Preference>("app_theme")?.setOnPreferenceChangeListener { _, newValue ->
            val theme = newValue as String
            when (theme) {
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
            true
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
                    app.database.hostKeyDao().deleteAllHostKeys()
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
            android.widget.Toast.makeText(requireContext(), "Import custom theme - Coming soon", android.widget.Toast.LENGTH_SHORT).show()
            // TODO: Implement file picker for JSON theme import
            // val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/json" }
            // themeImportLauncher.launch(intent)
            true
        }

        // Export current theme click listener
        findPreference<Preference>("export_current_theme")?.setOnPreferenceClickListener {
            android.widget.Toast.makeText(requireContext(), "Export theme - Coming soon", android.widget.Toast.LENGTH_SHORT).show()
            // TODO: Implement file save for JSON theme export
            // val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            //     type = "application/json"
            //     putExtra(Intent.EXTRA_TITLE, "my_theme.json")
            // }
            // themeExportLauncher.launch(intent)
            true
        }

        // Keyboard customization click listener
        findPreference<Preference>("customize_keyboard_layout")?.setOnPreferenceClickListener {
            val intent = android.content.Intent(requireContext(), KeyboardCustomizationActivity::class.java)
            startActivity(intent)
            true
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

        // Keep-alive interval change listener
        findPreference<Preference>("keep_alive_interval")?.setOnPreferenceChangeListener { _, newValue ->
            val interval = newValue as String
            val seconds = interval.toInt()
            android.widget.Toast.makeText(requireContext(), "Keep-alive interval: ${seconds}s", android.widget.Toast.LENGTH_SHORT).show()
            Logger.i("Settings", "Keep-alive interval changed to: $seconds")
            true
        }
    }
}

/**
 * Audit Log Settings Fragment
 */
class AuditSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_audit, rootKey)
        
        val app = requireActivity().application as TabSSHApplication
        
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
                        app.auditLogManager.deleteAllLogs()
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
                val logs = app.database.auditLogDao().getRecent(1000) // Get last 1000 logs
                
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val filename = "audit_logs_$timestamp.csv"
                
                val file = java.io.File(requireContext().getExternalFilesDir(null), filename)
                file.writeText(buildString {
                    append("Timestamp,Connection,Session,EventType,Command,Output\n")
                    logs.forEach { log ->
                        append("${log.timestamp},${log.connectionId},${log.sessionId},")
                        append("${log.eventType},\"${log.command ?: ""}\",")
                        append("\"${log.output ?: ""}\"\n")
                    }
                })
                
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
            app.database.connectionDao().getAllConnections().collect { connections ->
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
        
        // View logs
        findPreference<Preference>("view_logs")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LogViewerActivity::class.java))
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

