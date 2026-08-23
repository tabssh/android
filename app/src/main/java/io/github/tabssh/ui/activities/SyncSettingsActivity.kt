package io.github.tabssh.ui.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.tabssh.R
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.PendingSyncConflictCodec
import io.github.tabssh.sync.SAFSyncManager
import io.github.tabssh.sync.SyncFileStatus
import io.github.tabssh.sync.data.SyncDataCollector
import io.github.tabssh.sync.merge.ConflictResolver
import io.github.tabssh.sync.merge.SyncMergeCoordinator
import io.github.tabssh.sync.models.Conflict
import io.github.tabssh.sync.models.ConflictResolution
import io.github.tabssh.sync.worker.SyncWorkScheduler
import io.github.tabssh.ui.dialogs.ConflictResolutionDialog
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.showError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class SyncSettingsActivity : TabSSHActivity() {

    companion object {
        private const val TAG = "SyncSettingsActivity"
        private const val PREF_ENABLED       = "sync_enabled"
        private const val PREF_WIFI_ONLY     = "sync_wifi_only"
        private const val PREF_ON_CHANGE     = "sync_on_change"
        private const val PREF_CONNECTIONS          = "sync_connections"
        private const val PREF_IDENTITIES           = "sync_identities"
        private const val PREF_KEYS                 = "sync_keys"
        private const val PREF_SNIPPETS             = "sync_snippets"
        private const val PREF_THEMES               = "sync_themes"
        private const val PREF_HOST_KEYS            = "sync_host_keys"
        private const val PREF_GROUPS               = "sync_groups"
        private const val PREF_WORKSPACES           = "sync_workspaces"
        private const val PREF_MACROS               = "sync_macros"
        private const val PREF_MONITOR_SLOTS        = "sync_monitor_slots"
        private const val PREF_HYPERVISORS          = "sync_hypervisors"
        private const val PREF_HYPERVISOR_ACCOUNTS  = "sync_hypervisor_accounts"
        private const val PREF_VNC_HOSTS            = "sync_vnc_hosts"
        private const val PREF_VNC_IDENTITIES       = "sync_vnc_identities"
        private const val PREF_CLOUD_ACCOUNTS       = "sync_cloud_accounts"
        private const val PREF_CERTIFICATES         = "sync_certificates"
        private const val PREF_DASHBOARD            = "sync_dashboard"
        private const val PREF_PORT_FORWARDS        = "sync_port_forwards"
        private const val PREF_NETWORK_ROUTES       = "sync_network_routes"
        private const val PREF_PANE_GROUPS          = "sync_pane_groups"
        private const val PREF_CONTAINERS           = "sync_containers"
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
    private var resolvingDeferredConflicts = false

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
    // (row, prefKey, subtitle)
    private lateinit var syncItems: List<Triple<View, String, String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_settings)

        syncManager   = SAFSyncManager(this)
        workScheduler = SyncWorkScheduler(this)
        prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)

        // Toolbar
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        // File picker launchers
        createFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    syncManager.saveSyncUri(uri)
                    refresh()
                    toast(getString(R.string.sync_settings_toast_location_set))
                }
            }
        }
        openFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    syncManager.saveSyncUri(uri)
                    showVerifyPasswordForExistingFile(uri)
                }
            }
        }

        bindViews()
        wireListeners()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        checkDeferredConflicts()
    }

    /**
     * A headless (WorkManager) sync may have deferred conflicts it couldn't
     * resolve in the background — see [io.github.tabssh.sync.merge.SyncMergeCoordinator].
     * Resurface them here on every foreground open until the user resolves them.
     */
    private fun checkDeferredConflicts() {
        if (resolvingDeferredConflicts) return
        lifecycleScope.launch {
            val database = TabSSHDatabase.getDatabase(this@SyncSettingsActivity)
            val pendingDao = database.pendingSyncConflictDao()
            val rows = withContext(Dispatchers.IO) { pendingDao.getAll() }
            if (rows.isEmpty()) return@launch

            resolvingDeferredConflicts = true
            val conflicts = rows.map { PendingSyncConflictCodec.toConflict(it) }
            val resolutions = resolveConflictsInteractively(conflicts)
            withContext(Dispatchers.IO) {
                if (resolutions.isNotEmpty()) {
                    ConflictResolver(this@SyncSettingsActivity, database).applyResolutions(resolutions)
                }
                pendingDao.deleteAll()
            }
            resolvingDeferredConflicts = false
            toast(getString(R.string.sync_settings_toast_deferred_resolved))
        }
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
            Triple(findViewById(R.id.row_sync_connections),        PREF_CONNECTIONS,         getString(R.string.sync_settings_subtitle_connections)),
            Triple(findViewById(R.id.row_sync_identities),         PREF_IDENTITIES,          getString(R.string.sync_settings_subtitle_identities)),
            Triple(findViewById(R.id.row_sync_keys),               PREF_KEYS,                getString(R.string.sync_settings_subtitle_keys)),
            Triple(findViewById(R.id.row_sync_snippets),           PREF_SNIPPETS,            getString(R.string.sync_settings_subtitle_snippets)),
            Triple(findViewById(R.id.row_sync_themes),             PREF_THEMES,              getString(R.string.sync_settings_subtitle_themes)),
            Triple(findViewById(R.id.row_sync_host_keys),          PREF_HOST_KEYS,           getString(R.string.sync_settings_subtitle_host_keys)),
            Triple(findViewById(R.id.row_sync_groups),             PREF_GROUPS,              getString(R.string.sync_settings_subtitle_groups)),
            Triple(findViewById(R.id.row_sync_workspaces),         PREF_WORKSPACES,          getString(R.string.sync_settings_title_workspaces)),
            Triple(findViewById(R.id.row_sync_macros),             PREF_MACROS,              getString(R.string.sync_settings_subtitle_macros)),
            Triple(findViewById(R.id.row_sync_monitor_slots),      PREF_MONITOR_SLOTS,       getString(R.string.sync_settings_subtitle_monitor_slots)),
            Triple(findViewById(R.id.row_sync_hypervisors),        PREF_HYPERVISORS,         getString(R.string.sync_settings_subtitle_hypervisors)),
            Triple(findViewById(R.id.row_sync_hypervisor_accounts),PREF_HYPERVISOR_ACCOUNTS, getString(R.string.sync_settings_subtitle_hypervisor_accounts)),
            Triple(findViewById(R.id.row_sync_vnc_hosts),          PREF_VNC_HOSTS,           getString(R.string.sync_settings_subtitle_vnc_hosts)),
            Triple(findViewById(R.id.row_sync_vnc_identities),     PREF_VNC_IDENTITIES,      getString(R.string.sync_settings_subtitle_vnc_identities)),
            Triple(findViewById(R.id.row_sync_cloud_accounts),     PREF_CLOUD_ACCOUNTS,      getString(R.string.sync_settings_subtitle_cloud_accounts)),
            Triple(findViewById(R.id.row_sync_certificates),       PREF_CERTIFICATES,        getString(R.string.sync_settings_subtitle_certificates)),
            Triple(findViewById(R.id.row_sync_dashboard),          PREF_DASHBOARD,           getString(R.string.sync_settings_subtitle_dashboard)),
            Triple(findViewById(R.id.row_sync_port_forwards),      PREF_PORT_FORWARDS,       getString(R.string.sync_settings_subtitle_port_forwards)),
            Triple(findViewById(R.id.row_sync_network_routes),     PREF_NETWORK_ROUTES,      getString(R.string.sync_network_routes_summary)),
            Triple(findViewById(R.id.row_sync_containers),         PREF_CONTAINERS,          getString(R.string.sync_settings_subtitle_containers)),
            Triple(findViewById(R.id.row_sync_pane_groups),        PREF_PANE_GROUPS,         getString(R.string.sync_settings_subtitle_pane_groups))
        )
        val titles = listOf(
            getString(R.string.settings_connections), getString(R.string.sync_settings_title_identities), getString(R.string.identity_ssh_keys_title), getString(R.string.nav_item_snippets), getString(R.string.sync_settings_title_themes),
            getString(R.string.sync_settings_title_host_keys), getString(R.string.nav_item_groups), getString(R.string.sync_settings_title_workspaces), getString(R.string.sync_settings_title_macros), getString(R.string.sync_settings_title_monitor_slots),
            getString(R.string.infra_tab_hypervisors), getString(R.string.sync_settings_title_hypervisor_accounts), getString(R.string.nav_item_vnc_hosts), getString(R.string.identity_vnc_title),
            getString(R.string.activity_label_cloud_accounts), getString(R.string.sync_settings_title_certificates), getString(R.string.container_manager_tab_dashboard), getString(R.string.routing_section_forwards_title),
            getString(R.string.sync_network_routes), getString(R.string.container_tab_title), getString(R.string.sync_settings_title_pane_groups)
        )
        val defaults = listOf(
            true, true, true, true, true,
            true, true, true, true, true,
            true, true, true, true,
            true, true,
            false, true, true, true,
            true
        )
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

        // Enable switch — restore persisted state before wiring the listener
        // so that entering and leaving the screen doesn't flip it off.
        switchEnabled.isChecked = prefs.getBoolean(PREF_ENABLED, false)
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_ENABLED, checked).apply()
            if (checked) {
                lifecycleScope.launch {
                    val status = withContext(Dispatchers.IO) { syncManager.checkSyncFile() }
                    withContext(Dispatchers.Main) {
                        if (status == SyncFileStatus.OK) {
                            workScheduler.schedulePeriodicSync()
                            refresh()
                        } else {
                            switchEnabled.isChecked = false
                            prefs.edit().putBoolean(PREF_ENABLED, false).apply()
                            showError(
                                getString(R.string.sync_file_error_message, status.toString()),
                                getString(R.string.sync_file_error_title)
                            )
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
        switchWifiOnly.isChecked = prefs.getBoolean(PREF_WIFI_ONLY, false)
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
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_settings_force_upload_title)
                .setMessage(R.string.sync_settings_force_upload_message)
                .setPositiveButton(R.string.upload_file) { _, _ -> performSync() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        findViewById<View>(R.id.btn_force_download).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_settings_force_download_title)
                .setMessage(R.string.sync_settings_force_download_message)
                .setPositiveButton(R.string.download_file) { _, _ -> performDownload() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        findViewById<View>(R.id.btn_view_sync_log).setOnClickListener {
            startActivity(Intent(this, SyncLogActivity::class.java))
        }
        findViewById<View>(R.id.btn_clear_config).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_settings_clear_config_title)
                .setMessage(R.string.sync_settings_clear_config_message)
                .setPositiveButton(R.string.sync_settings_clear) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { syncManager.clearConfiguration() }
                        switchEnabled.isChecked = false
                        workScheduler.cancelPeriodicSync()
                        refresh()
                        toast(getString(R.string.sync_settings_toast_config_cleared))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
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
            ?: getString(R.string.sync_settings_location_summary_default)

        // Password badge
        badgePassword.visibility       = if (hasPassword) View.VISIBLE else View.GONE
        textPasswordSummary.text = if (hasPassword) getString(R.string.sync_settings_password_summary_set) else getString(R.string.sync_settings_password_summary_required)

        // Enable toggle
        switchEnabled.isEnabled = isReady
        textToggleHint.text = when {
            !hasLocation && !hasPassword -> getString(R.string.sync_settings_toggle_hint_location_and_password)
            !hasLocation -> getString(R.string.sync_settings_toggle_hint_location)
            !hasPassword -> getString(R.string.sync_settings_toggle_hint_password)
            else         -> getString(R.string.sync_settings_toggle_hint_ready)
        }

        // Status card
        when {
            isEnabled -> {
                statusIcon.text = "🔄"
                statusTitle.text = getString(R.string.sync_settings_status_active)
                statusSubtitle.text = getString(R.string.sync_settings_status_last_sync, formatLastSync(syncManager.getLastSyncTime()))
                btnSyncNow.visibility = View.VISIBLE
                textLastSync.visibility = View.VISIBLE
                textLastSync.text = getString(R.string.sync_settings_last_sync_short, formatLastSync(syncManager.getLastSyncTime()))
            }
            isReady -> {
                statusIcon.text = "✅"
                statusTitle.text = getString(R.string.sync_settings_status_ready)
                statusSubtitle.text = getString(R.string.sync_settings_status_ready_subtitle)
                btnSyncNow.visibility = View.GONE
                textLastSync.visibility = View.GONE
            }
            else -> {
                statusIcon.text = "⚙️"
                statusTitle.text = getString(R.string.sync_settings_status_not_configured)
                statusSubtitle.text = buildString {
                    if (!hasLocation) append(getString(R.string.sync_settings_status_bullet_choose_location) + "\n")
                    if (!hasPassword) append(getString(R.string.sync_settings_status_bullet_set_password))
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
            add(getString(R.string.sync_settings_location_option_create))
            add(getString(R.string.sync_settings_location_option_open))
            if (hasExisting) add(getString(R.string.sync_settings_location_option_clear))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_settings_location_dialog_title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> createFileLauncher.launch(syncManager.getCreateFileIntent())
                    1 -> openFileLauncher.launch(syncManager.getOpenFileIntent())
                    2 -> lifecycleScope.launch {
                        withContext(Dispatchers.IO) { syncManager.clearConfiguration() }
                        refresh()
                        toast(getString(R.string.sync_settings_toast_location_cleared))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPasswordDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_sync_password, null)
        val passwordInput  = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_password)
        val confirmInput   = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_confirm)
        val passwordLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_password)
        val confirmLayout  = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_confirm)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_settings_password_dialog_title)
            .setMessage(R.string.sync_settings_password_dialog_message)
            .setView(view)
            .setPositiveButton(R.string.sync_settings_set_password, null)
            .setNegativeButton(R.string.cancel, null)
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
                pw.length < 8 -> passwordLayout?.error = errTooShort
                pw != cfm     -> confirmLayout?.error  = errMismatch
                else -> {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { syncManager.setSyncPassword(pw) }
                        refresh()
                        toast(getString(R.string.sync_settings_toast_password_set))
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    /**
     * After the user picks an EXISTING sync file, prompt for the password
     * and verify it can decrypt the file before accepting it.
     * If the file is empty (not yet written) the password is accepted without verification.
     */
    private fun showVerifyPasswordForExistingFile(uri: Uri) {
        val view = layoutInflater.inflate(R.layout.dialog_sync_password, null)
        val passwordInput  = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_password)
        val passwordLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_password)
        val confirmLayout  = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_confirm)

        // Hide the confirm field — not needed when opening an existing file
        confirmLayout?.visibility = View.GONE

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_settings_verify_password_dialog_title)
            .setMessage(R.string.sync_settings_verify_password_dialog_message)
            .setView(view)
            .setPositiveButton(R.string.sync_settings_verify_and_use, null)
            .setNegativeButton(R.string.cancel) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { syncManager.clearConfiguration() }
                    refresh()
                }
            }
            .setCancelable(false)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pw = passwordInput?.text?.toString() ?: ""
            passwordLayout?.error = null
            if (pw.length < 8) {
                passwordLayout?.error = getString(R.string.sync_password_too_short)
                return@setOnClickListener
            }
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            btn.text = getString(R.string.sync_settings_verifying)
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { syncManager.verifyPassword(uri, pw) }
                if (ok) {
                    withContext(Dispatchers.IO) { syncManager.setSyncPassword(pw) }
                    withContext(Dispatchers.Main) {
                        refresh()
                        toast(getString(R.string.sync_settings_toast_location_set_and_verified))
                        dialog.dismiss()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        btn.isEnabled = true
                        btn.text = getString(R.string.sync_settings_verify_and_use)
                        passwordLayout?.error = getString(R.string.sync_settings_error_wrong_password)
                    }
                }
            }
        }
    }

    private fun showFrequencyPicker() {
        val current = prefs.getString(PREF_FREQUENCY, "1h") ?: "1h"
        val currentIdx = FREQUENCY_KEYS.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_settings_frequency_dialog_title)
            .setSingleChoiceItems(FREQUENCY_VALUES, currentIdx) { d, which ->
                prefs.edit().putString(PREF_FREQUENCY, FREQUENCY_KEYS[which]).apply()
                textFrequencyValue.text = FREQUENCY_VALUES[which]
                if (switchEnabled.isChecked) workScheduler.schedulePeriodicSync()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performSync() {
        if (!syncManager.isConfigured()) { toast(getString(R.string.sync_settings_toast_not_configured)); return }
        progressSync.visibility = View.VISIBLE
        btnSyncNow.isEnabled = false
        lifecycleScope.launch {
            try {
                val (payload, ok) = withContext(Dispatchers.IO) {
                    // H6 — two-way: pull and merge the peer state before
                    // re-uploading so remote changes are ingested and remote
                    // deletes are not resurrected by the union upload.
                    val collector = SyncDataCollector(this@SyncSettingsActivity)
                    val remote = syncManager.download()
                    if (remote != null) {
                        // §9.6 three-way merge. Foreground: hand conflicts to the
                        // resolution dialog on the main thread.
                        SyncMergeCoordinator(this@SyncSettingsActivity).merge(
                            remote,
                            syncManager.getEncryptionPassword(),
                            resolveConflicts = { conflicts -> resolveConflictsInteractively(conflicts) }
                        )
                    }
                    val p = collector.collectAll()
                    val uploaded = syncManager.upload(p)
                    // Refresh the shadow baseline only on a successful upload.
                    if (uploaded) collector.snapshotState()
                    p to uploaded
                }
                withContext(Dispatchers.Main) {
                    progressSync.visibility = View.GONE
                    btnSyncNow.isEnabled = true
                    if (ok) { refresh(); toast(getString(R.string.sync_settings_toast_sync_complete)) }
                    else toast(getString(R.string.sync_settings_toast_sync_failed_check_log))
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Sync failed", e)
                withContext(Dispatchers.Main) {
                    progressSync.visibility = View.GONE
                    btnSyncNow.isEnabled = true
                    toast(getString(R.string.sync_settings_toast_sync_failed, e.message))
                }
            }
        }
    }

    private fun performDownload() {
        if (!syncManager.isConfigured()) { toast(getString(R.string.sync_settings_toast_not_configured)); return }
        progressSync.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) { syncManager.download() }
                if (payload != null) {
                    withContext(Dispatchers.IO) {
                        // §9.6 three-way merge with interactive resolution.
                        SyncMergeCoordinator(this@SyncSettingsActivity).merge(
                            payload,
                            syncManager.getEncryptionPassword(),
                            resolveConflicts = { conflicts -> resolveConflictsInteractively(conflicts) }
                        )
                        // Refresh the shadow baseline so the deletes just
                        // applied are not re-detected as local deletions by the
                        // next collect's tombstone backstop.
                        SyncDataCollector(this@SyncSettingsActivity).snapshotState()
                    }
                    withContext(Dispatchers.Main) {
                        progressSync.visibility = View.GONE
                        refresh()
                        toast(getString(R.string.sync_settings_toast_download_complete))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressSync.visibility = View.GONE
                        toast(getString(R.string.sync_settings_toast_nothing_to_download))
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    progressSync.visibility = View.GONE
                    toast(getString(R.string.sync_settings_toast_download_failed, e.message))
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    // Bridge the callback-based ConflictResolutionDialog to a suspend function
    // for SyncMergeCoordinator. The dialog must run on the main thread; the
    // coroutine resumes once the user has decided every conflict.
    private suspend fun resolveConflictsInteractively(
        conflicts: List<Conflict>
    ): List<ConflictResolution> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            ConflictResolutionDialog(this@SyncSettingsActivity, conflicts) { resolutions ->
                if (cont.isActive) cont.resume(resolutions)
            }.show()
        }
    }

    // DateUtils renders the "N minutes/hours/days ago" phrasing in the device
    // locale, so the relative time never has to be hand-assembled here.
    private fun formatLastSync(ts: Long): String {
        if (ts == 0L) return getString(R.string.sync_last_never)
        val now = System.currentTimeMillis()
        if (now - ts < DateUtils.MINUTE_IN_MILLIS) return getString(R.string.sync_last_just_now)
        return DateUtils.getRelativeTimeSpanString(ts, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

}
