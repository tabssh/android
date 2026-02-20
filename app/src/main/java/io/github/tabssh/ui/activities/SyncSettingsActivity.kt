package io.github.tabssh.ui.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import io.github.tabssh.R
import io.github.tabssh.sync.SAFSyncManager
import io.github.tabssh.sync.SyncFileStatus
import io.github.tabssh.sync.data.SyncDataApplier
import io.github.tabssh.sync.data.SyncDataCollector
import io.github.tabssh.sync.worker.SyncWorkScheduler
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SyncSettingsActivity"
        private const val PREF_WIFI_ONLY     = "sync_wifi_only"
        private const val PREF_ON_CHANGE     = "sync_on_change"
        private const val PREF_CONNECTIONS   = "sync_connections"
        private const val PREF_IDENTITIES    = "sync_identities"
        private const val PREF_KEYS          = "sync_keys"
        private const val PREF_SNIPPETS      = "sync_snippets"
        private const val PREF_THEMES        = "sync_themes"
        private const val PREF_FREQUENCY     = "sync_frequency"
        private val FREQUENCY_LABELS = mapOf(
            "manual" to "Manual only",
            "15min"  to "Every 15 minutes",
            "30min"  to "Every 30 minutes",
            "1h"     to "Every hour",
            "3h"     to "Every 3 hours",
            "6h"     to "Every 6 hours",
            "12h"    to "Every 12 hours",
            "24h"    to "Every 24 hours"
        )
        private val FREQUENCY_KEYS = FREQUENCY_LABELS.keys.toTypedArray()
        private val FREQUENCY_VALUES = FREQUENCY_LABELS.values.toTypedArray()
    }

    private lateinit var syncManager: SAFSyncManager
    private lateinit var workScheduler: SyncWorkScheduler
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var createFileLauncher: ActivityResultLauncher<Intent>
    private lateinit var openFileLauncher: ActivityResultLauncher<Intent>

    // Views
    private lateinit var statusIcon: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var btnSyncNow: MaterialButton
    private lateinit var progressSync: View
    private lateinit var textLastSync: TextView
    private lateinit var badgeLocation: TextView
    private lateinit var textLocationSummary: TextView
    private lateinit var badgePassword: TextView
    private lateinit var textPasswordSummary: TextView
    private lateinit var textToggleHint: TextView
    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var sectionOptions: LinearLayout
    private lateinit var textFrequencyValue: TextView
    private lateinit var switchWifiOnly: MaterialSwitch
    private lateinit var switchOnChange: MaterialSwitch

    // What-to-sync rows
    private lateinit var syncItems: List<Triple<View, String, String>>  // (row, prefKey, subtitle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_settings)

        syncManager   = SAFSyncManager(this)
        workScheduler = SyncWorkScheduler(this)
        prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this)

        // Toolbar
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // File picker launchers
        createFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    syncManager.saveSyncUri(uri)
                    refresh()
                    toast("Sync location set")
                }
            }
        }
        openFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    syncManager.saveSyncUri(uri)
                    refresh()
                    toast("Sync location set")
                }
            }
        }

        bindViews()
        wireListeners()
        refresh()
    }

    private fun bindViews() {
        statusIcon          = findViewById(R.id.text_status_icon)
        statusTitle         = findViewById(R.id.text_status_title)
        statusSubtitle      = findViewById(R.id.text_status_subtitle)
        btnSyncNow          = findViewById(R.id.btn_sync_now)
        progressSync        = findViewById(R.id.progress_sync)
        textLastSync        = findViewById(R.id.text_last_sync)
        badgeLocation       = findViewById(R.id.badge_location)
        textLocationSummary = findViewById(R.id.text_location_summary)
        badgePassword       = findViewById(R.id.badge_password)
        textPasswordSummary = findViewById(R.id.text_password_summary)
        textToggleHint      = findViewById(R.id.text_toggle_hint)
        switchEnabled       = findViewById(R.id.switch_sync_enabled)
        sectionOptions      = findViewById(R.id.section_options)
        textFrequencyValue  = findViewById(R.id.text_frequency_value)
        switchWifiOnly      = findViewById(R.id.switch_wifi_only)
        switchOnChange      = findViewById(R.id.switch_sync_on_change)

        // Bind what-to-sync rows
        syncItems = listOf(
            Triple(findViewById(R.id.row_sync_connections), PREF_CONNECTIONS, "SSH connection profiles"),
            Triple(findViewById(R.id.row_sync_identities),  PREF_IDENTITIES,  "Reusable credentials"),
            Triple(findViewById(R.id.row_sync_keys),        PREF_KEYS,        "Key metadata (private keys stay local)"),
            Triple(findViewById(R.id.row_sync_snippets),    PREF_SNIPPETS,    "Command snippets"),
            Triple(findViewById(R.id.row_sync_themes),      PREF_THEMES,      "Custom terminal themes")
        )
        val titles = listOf("Connections", "Identities", "SSH Keys", "Snippets", "Themes")
        val defaults = listOf(true, true, false, true, true)
        syncItems.forEachIndexed { i, (row, prefKey, subtitle) ->
            row.findViewById<TextView>(R.id.text_sync_item_title).text  = titles[i]
            row.findViewById<TextView>(R.id.text_sync_item_subtitle).text = subtitle
            val sw = row.findViewById<MaterialSwitch>(R.id.switch_sync_item)
            sw.isChecked = prefs.getBoolean(prefKey, defaults[i])
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(prefKey, checked).apply()
            }
        }
    }

    private fun wireListeners() {
        // Location row
        findViewById<View>(R.id.row_location).setOnClickListener { showLocationOptions() }

        // Password row
        findViewById<View>(R.id.row_password).setOnClickListener { showPasswordDialog() }

        // Enable switch
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                lifecycleScope.launch {
                    val status = syncManager.checkSyncFile()
                    withContext(Dispatchers.Main) {
                        if (status == SyncFileStatus.OK) {
                            workScheduler.schedulePeriodicSync()
                            refresh()
                        } else {
                            switchEnabled.isChecked = false
                            showError("File error", "Cannot access sync file: $status\nPlease reconfigure the location.")
                        }
                    }
                }
            } else {
                workScheduler.cancelPeriodicSync()
                refresh()
            }
        }

        // Sync Now
        btnSyncNow.setOnClickListener { performSync() }

        // Frequency row
        findViewById<View>(R.id.row_frequency).setOnClickListener { showFrequencyPicker() }

        // Options switches
        switchWifiOnly.isChecked = prefs.getBoolean(PREF_WIFI_ONLY, true)
        switchWifiOnly.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_WIFI_ONLY, checked).apply()
            if (switchEnabled.isChecked) workScheduler.schedulePeriodicSync()
        }
        switchOnChange.isChecked = prefs.getBoolean(PREF_ON_CHANGE, true)
        switchOnChange.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_ON_CHANGE, checked).apply()
        }

        // Advanced
        findViewById<View>(R.id.btn_force_upload).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Force Upload")
                .setMessage("Upload local data to the sync file, overwriting remote.\n\nContinue?")
                .setPositiveButton("Upload") { _, _ -> performSync() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<View>(R.id.btn_force_download).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Force Download")
                .setMessage("Download remote data and overwrite all local data.\n\nThis cannot be undone. Continue?")
                .setPositiveButton("Download") { _, _ -> performDownload() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<View>(R.id.btn_clear_config).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Configuration")
                .setMessage("Remove sync setup. Your local data is NOT affected.")
                .setPositiveButton("Clear") { _, _ ->
                    syncManager.clearConfiguration()
                    switchEnabled.isChecked = false
                    workScheduler.cancelPeriodicSync()
                    refresh()
                    toast("Sync configuration cleared")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── State refresh ────────────────────────────────────────────────────────

    private fun refresh() {
        val hasLocation = syncManager.getSyncUri() != null
        val hasPassword = syncManager.hasPassword()
        val isEnabled   = syncManager.isConfigured() && switchEnabled.isChecked
        val isReady     = hasLocation && hasPassword

        // Location badge
        badgeLocation.visibility       = if (hasLocation) View.VISIBLE else View.GONE
        textLocationSummary.text = syncManager.getSyncLocationName()
            ?: "Tap to choose a file in any cloud app"

        // Password badge
        badgePassword.visibility       = if (hasPassword) View.VISIBLE else View.GONE
        textPasswordSummary.text = if (hasPassword) "Set — tap to change" else "Required — encrypts all synced data (AES-256)"

        // Enable toggle
        switchEnabled.isEnabled = isReady
        textToggleHint.text = when {
            !hasLocation && !hasPassword -> "Set location and password first"
            !hasLocation -> "Set sync location first"
            !hasPassword -> "Set encryption password first"
            else         -> "Sync across devices"
        }

        // Status card
        when {
            isEnabled -> {
                statusIcon.text = "🔄"
                statusTitle.text = "Sync Active"
                statusSubtitle.text = "Last sync: ${formatLastSync(syncManager.getLastSyncTime())}"
                btnSyncNow.visibility = View.VISIBLE
                textLastSync.visibility = View.VISIBLE
                textLastSync.text = "Last: ${formatLastSync(syncManager.getLastSyncTime())}"
            }
            isReady -> {
                statusIcon.text = "✅"
                statusTitle.text = "Ready"
                statusSubtitle.text = "Toggle Enable Sync to start"
                btnSyncNow.visibility = View.GONE
                textLastSync.visibility = View.GONE
            }
            else -> {
                statusIcon.text = "⚙️"
                statusTitle.text = "Not configured"
                statusSubtitle.text = buildString {
                    if (!hasLocation) append("• Choose sync location\n")
                    if (!hasPassword) append("• Set encryption password")
                }.trimEnd()
                btnSyncNow.visibility = View.GONE
                textLastSync.visibility = View.GONE
            }
        }

        // Options section
        sectionOptions.visibility = if (isEnabled) View.VISIBLE else View.GONE

        // Frequency label
        val freqKey = prefs.getString(PREF_FREQUENCY, "1h") ?: "1h"
        textFrequencyValue.text = FREQUENCY_LABELS[freqKey] ?: "Every hour"
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun showLocationOptions() {
        val hasExisting = syncManager.getSyncUri() != null
        val options = buildList {
            add("Create new sync file")
            add("Open existing sync file")
            if (hasExisting) add("Clear current location")
        }
        AlertDialog.Builder(this)
            .setTitle("Sync Location")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> createFileLauncher.launch(syncManager.getCreateFileIntent())
                    1 -> openFileLauncher.launch(syncManager.getOpenFileIntent())
                    2 -> { syncManager.clearConfiguration(); refresh(); toast("Location cleared") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasswordDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_sync_password, null)
        val passwordInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_password)
        val confirmInput  = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_confirm)
        val passwordLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_password)
        val confirmLayout  = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_confirm)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Encryption Password")
            .setMessage("Same password required on all devices. Min 8 characters.")
            .setView(view)
            .setPositiveButton("Set Password", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pw  = passwordInput?.text.toString()
            val cfm = confirmInput?.text.toString()
            passwordLayout?.error = null
            confirmLayout?.error  = null
            val errTooShort = getString(R.string.sync_password_too_short)
            val errMismatch = getString(R.string.sync_password_mismatch)
            when {
                pw.length < 8    -> passwordLayout?.error = errTooShort
                pw != cfm        -> confirmLayout?.error  = errMismatch
                else -> {
                    syncManager.setSyncPassword(pw)
                    refresh()
                    toast("Password set")
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showFrequencyPicker() {
        val current = prefs.getString(PREF_FREQUENCY, "1h") ?: "1h"
        val currentIdx = FREQUENCY_KEYS.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Sync Frequency")
            .setSingleChoiceItems(FREQUENCY_VALUES, currentIdx) { d, which ->
                prefs.edit().putString(PREF_FREQUENCY, FREQUENCY_KEYS[which]).apply()
                textFrequencyValue.text = FREQUENCY_VALUES[which]
                if (switchEnabled.isChecked) workScheduler.schedulePeriodicSync()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSync() {
        if (!syncManager.isConfigured()) { toast("Sync not configured"); return }
        progressSync.visibility = View.VISIBLE
        btnSyncNow.isEnabled = false
        lifecycleScope.launch {
            try {
                val payload = SyncDataCollector(this@SyncSettingsActivity).collectAll()
                val ok = syncManager.upload(payload)
                withContext(Dispatchers.Main) {
                    progressSync.visibility = View.GONE
                    btnSyncNow.isEnabled = true
                    if (ok) { refresh(); toast("Sync complete") }
                    else toast("Sync failed — check the log")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Sync failed", e)
                withContext(Dispatchers.Main) {
                    progressSync.visibility = View.GONE
                    btnSyncNow.isEnabled = true
                    toast("Sync failed: ${e.message}")
                }
            }
        }
    }

    private fun performDownload() {
        if (!syncManager.isConfigured()) { toast("Sync not configured"); return }
        progressSync.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val payload = syncManager.download()
                if (payload != null) {
                    SyncDataApplier(this@SyncSettingsActivity).applyAll(payload)
                    withContext(Dispatchers.Main) {
                        progressSync.visibility = View.GONE
                        refresh()
                        toast("Download complete")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressSync.visibility = View.GONE
                        toast("Nothing to download")
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    progressSync.visibility = View.GONE
                    toast("Download failed: ${e.message}")
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun formatLastSync(ts: Long): String {
        if (ts == 0L) return "Never"
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000      -> "Just now"
            diff < 3_600_000   -> "${diff / 60_000}m ago"
            diff < 86_400_000  -> "${diff / 3_600_000}h ago"
            else               -> "${diff / 86_400_000}d ago"
        }
    }

    private fun showError(title: String, msg: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
