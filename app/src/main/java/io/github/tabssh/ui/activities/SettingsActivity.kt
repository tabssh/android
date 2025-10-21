package io.github.tabssh.ui.activities

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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsMainFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}

class SettingsMainFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey)
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
    }

    private fun clearKnownHosts() {
        try {
            val app = requireActivity().application as TabSSHApplication
            // Clear host keys from database
            lifecycleScope.launch {
                app.database.hostKeyDao().deleteAllHostKeys()
                android.widget.Toast.makeText(requireContext(), "Known hosts cleared", android.widget.Toast.LENGTH_SHORT).show()
                Logger.i("Settings", "Cleared all known hosts")
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
    }
}

class ConnectionSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_connection, rootKey)
    }
}
