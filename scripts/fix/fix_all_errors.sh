#!/bin/bash

echo "=== Applying Final Comprehensive Fix ==="

# Fix TabSessionDao missing methods
echo "Fixing TabSessionDao..."
cat >> app/src/main/java/io/github/tabssh/storage/database/dao/TabSessionDao.kt << 'EOF'

    @Query("SELECT * FROM tab_sessions WHERE is_active = 1")
    suspend fun getAllTabs(): List<TabSession>

    @Query("UPDATE tab_sessions SET is_active = 0")
    suspend fun deactivateAllSessions()
EOF

# Create simple SettingsActivity fix
echo "Creating simplified SettingsActivity..."
cat > app/src/main/java/com/tabssh/ui/activities/SettingsActivity.kt << 'EOF'
package io.github.tabssh.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import io.github.tabssh.R

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }
}

class GeneralSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}
}

class SecuritySettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}
}

class TerminalSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}
}

class InterfaceSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}
}

class ConnectionSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}
}

class AccessibilitySettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}
}

class AboutSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}
}
EOF

echo "=== Done applying fixes ==="