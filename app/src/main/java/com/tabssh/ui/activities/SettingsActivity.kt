package io.github.tabssh.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import io.github.tabssh.R
import io.github.tabssh.databinding.ActivitySettingsBinding
import io.github.tabssh.ui.fragments.settings.*
import io.github.tabssh.utils.logging.Logger

/**
 * Settings activity with comprehensive preference categories
 * Provides organized access to all TabSSH settings
 */
class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    
    companion object {
        const val EXTRA_SETTINGS_CATEGORY = "settings_category"
        
        // Settings categories
        const val CATEGORY_GENERAL = "general"
        const val CATEGORY_SECURITY = "security"
        const val CATEGORY_TERMINAL = "terminal"
        const val CATEGORY_INTERFACE = "interface"
        const val CATEGORY_CONNECTIONS = "connections"
        const val CATEGORY_ACCESSIBILITY = "accessibility"
        const val CATEGORY_ABOUT = "about"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        
        // Handle direct category navigation
        val category = intent.getStringExtra(EXTRA_SETTINGS_CATEGORY)
        
        if (savedInstanceState == null) {
            if (category != null) {
                // Navigate directly to specific category
                navigateToCategory(category)
            } else {
                // Show main settings menu
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settings_container, MainSettingsFragment())
                    .commit()
            }
        }
        
        Logger.d("SettingsActivity", "Settings activity created, category: $category")
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.settings_title)
        }
        
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }
    
    private fun navigateToCategory(category: String) {
        val fragment = when (category) {
            CATEGORY_GENERAL -> GeneralSettingsFragment()
            CATEGORY_SECURITY -> SecuritySettingsFragment()
            CATEGORY_TERMINAL -> TerminalSettingsFragment()
            CATEGORY_INTERFACE -> InterfaceSettingsFragment()
            CATEGORY_CONNECTIONS -> ConnectionSettingsFragment()
            CATEGORY_ACCESSIBILITY -> AccessibilitySettingsFragment()
            CATEGORY_ABOUT -> AboutSettingsFragment()
            else -> MainSettingsFragment()
        }
        
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
        
        // Update toolbar title
        supportActionBar?.title = when (category) {
            CATEGORY_GENERAL -> getString(R.string.settings_general)
            CATEGORY_SECURITY -> getString(R.string.settings_security)
            CATEGORY_TERMINAL -> getString(R.string.settings_terminal)
            CATEGORY_INTERFACE -> getString(R.string.settings_interface)
            CATEGORY_CONNECTIONS -> getString(R.string.settings_connections)
            CATEGORY_ACCESSIBILITY -> getString(R.string.settings_accessibility)
            CATEGORY_ABOUT -> getString(R.string.settings_about)
            else -> getString(R.string.settings_title)
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}

/**
 * Main settings fragment showing all categories
 */
class MainSettingsFragment : PreferenceFragmentCompat() {
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey)
        
        // Set up category click handlers
        setupCategoryNavigation()
        
        Logger.d("MainSettingsFragment", "Main settings fragment created")
    }
    
    private fun setupCategoryNavigation() {
        findPreference<androidx.preference.Preference>("category_general")?.setOnPreferenceClickListener {
            navigateToCategory(SettingsActivity.CATEGORY_GENERAL)
            true
        }
        
        findPreference<androidx.preference.Preference>("category_security")?.setOnPreferenceClickListener {
            navigateToCategory(SettingsActivity.CATEGORY_SECURITY)
            true
        }
        
        findPreference<androidx.preference.Preference>("category_terminal")?.setOnPreferenceClickListener {
            navigateToCategory(SettingsActivity.CATEGORY_TERMINAL)
            true
        }
        
        findPreference<androidx.preference.Preference>("category_interface")?.setOnPreferenceClickListener {
            navigateToCategory(SettingsActivity.CATEGORY_INTERFACE)
            true
        }
        
        findPreference<androidx.preference.Preference>("category_connections")?.setOnPreferenceClickListener {
            navigateToCategory(SettingsActivity.CATEGORY_CONNECTIONS)
            true
        }
        
        findPreference<androidx.preference.Preference>("category_accessibility")?.setOnPreferenceClickListener {
            navigateToCategory(SettingsActivity.CATEGORY_ACCESSIBILITY)
            true
        }
        
        findPreference<androidx.preference.Preference>("category_about")?.setOnPreferenceClickListener {
            navigateToCategory(SettingsActivity.CATEGORY_ABOUT)
            true
        }
    }
    
    private fun navigateToCategory(category: String) {
        val activity = requireActivity() as SettingsActivity
        
        val fragment = when (category) {
            SettingsActivity.CATEGORY_GENERAL -> GeneralSettingsFragment()
            SettingsActivity.CATEGORY_SECURITY -> SecuritySettingsFragment()
            SettingsActivity.CATEGORY_TERMINAL -> TerminalSettingsFragment()
            SettingsActivity.CATEGORY_INTERFACE -> InterfaceSettingsFragment()
            SettingsActivity.CATEGORY_CONNECTIONS -> ConnectionSettingsFragment()
            SettingsActivity.CATEGORY_ACCESSIBILITY -> AccessibilitySettingsFragment()
            SettingsActivity.CATEGORY_ABOUT -> AboutSettingsFragment()
            else -> return
        }
        
        parentFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}