package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.backup.BackupManager
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Import / Export screen.
 * All import/export logic previously in MainActivity lives here.
 * Launched from Settings → Import / Export preference.
 */
class ImportExportActivity : TabSSHActivity() {

    private lateinit var app: TabSSHApplication
    // Nullable so click handlers can detect "not yet ready" without crashing.
    // Initialized on Dispatchers.IO in onCreate because BackupManager's constructor
    // seeds BouncyCastle's DRBG which can block briefly on cold start.
    private var backupManager: BackupManager? = null

    // SAF launcher — restore from a ZIP backup
    private val importConnectionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { showRestoreModeDialog(it) }
    }

    // SAF launcher — write a ZIP backup
    private val exportConnectionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { exportBackupToUri(it) }
    }

    // SAF launcher — open an SSH config text file
    private val importSSHConfigLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importSSHConfigFromUri(it) }
    }

    // SAF launcher — open a CSV/JSON/PuTTY .reg/Terraform file for bulk import
    private val bulkImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { bulkImportFromUri(it) }
    }

    // SAF launcher — write an SSH config text file
    private val exportSshConfigLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { exportSshConfigToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_export)

        app = application as TabSSHApplication

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // BackupManager's constructor calls SyncEncryptor() which seeds BouncyCastle's
        // DRBG — a potentially blocking operation. Initialise on IO so onCreate returns
        // immediately; all usages are inside coroutines triggered by user actions.
        lifecycleScope.launch(Dispatchers.IO) {
            backupManager = BackupManager(this@ImportExportActivity)
        }

        // Wire card click listeners
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_import_ssh)
            .setOnClickListener {
                io.github.tabssh.ui.dialogs.ImportExportChooserDialog.showImportSource(
                    this,
                    onFile = { importSSHConfigLauncher.launch(arrayOf("*/*")) },
                    onPaste = {
                        io.github.tabssh.ui.dialogs.TextImportDialog.show(
                            this, getString(R.string.import_export_import_ssh_config_title)
                        ) { text -> importSSHConfigText(text) }
                    }
                )
            }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_export_ssh)
            .setOnClickListener {
                io.github.tabssh.ui.dialogs.ImportExportChooserDialog.showExportTarget(
                    this,
                    onFile = { exportSshConfigLauncher.launch("ssh_config_${System.currentTimeMillis() / 1000}.txt") },
                    onText = { showSshConfigExportText() }
                )
            }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_bulk_import)
            .setOnClickListener {
                io.github.tabssh.ui.dialogs.ImportExportChooserDialog.showImportSource(
                    this,
                    onFile = { bulkImportLauncher.launch(arrayOf("*/*")) },
                    onPaste = {
                        io.github.tabssh.ui.dialogs.TextImportDialog.show(
                            this, getString(R.string.import_export_bulk_import_title)
                        ) { text -> bulkImportText(text) }
                    }
                )
            }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_pair_qr)
            .setOnClickListener {
                startActivity(Intent(this, ImportFromQrActivity::class.java))
            }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_import_backup)
            .setOnClickListener {
                importConnectionsLauncher.launch(arrayOf("application/zip", "application/json"))
            }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_export_backup)
            .setOnClickListener {
                exportConnectionsLauncher.launch("tabssh_connections_${System.currentTimeMillis()}.zip")
            }
    }

    /**
     * Ask the user whether to merge the backup into the current data (default,
     * safe) or replace everything with an exact snapshot of the backup, then
     * continue the restore flow with that choice.
     */
    private fun showRestoreModeDialog(uri: android.net.Uri) {
        val radioGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            setPadding(64, 32, 64, 0)
        }
        val mergeOption = android.widget.RadioButton(this).apply {
            text = getString(R.string.restore_mode_merge)
            id = android.view.View.generateViewId()
            isChecked = true
        }
        val replaceOption = android.widget.RadioButton(this).apply {
            text = getString(R.string.restore_mode_replace)
            id = android.view.View.generateViewId()
        }
        radioGroup.addView(mergeOption)
        radioGroup.addView(replaceOption)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_mode_title)
            .setView(radioGroup)
            .setPositiveButton(R.string.restore_mode_continue) { _, _ ->
                val replaceMode = radioGroup.checkedRadioButtonId == replaceOption.id
                importBackupFromUri(uri, replaceMode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Attempt to restore a backup from [uri] without a password first.
     * Falls back to the password dialog when the backup appears to be encrypted.
     */
    private fun importBackupFromUri(uri: android.net.Uri, replaceMode: Boolean) {
        lifecycleScope.launch {
            try {
                val bm = backupManager ?: run {
                    android.widget.Toast.makeText(this@ImportExportActivity, getString(R.string.import_export_backup_initialising), android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                val result = bm.restoreBackup(uri, password = null, overwriteExisting = false, replaceMode = replaceMode)

                if (result.success) {
                    showImportSuccessDialog(result)
                    Logger.i("ImportExportActivity", "Imported backup successfully")
                } else {
                    throw Exception(result.message)
                }

            } catch (e: Exception) {
                if (e.message?.contains("encrypted", ignoreCase = true) == true ||
                    e.message?.contains("password", ignoreCase = true) == true ||
                    e.message?.contains("decrypt", ignoreCase = true) == true) {
                    showImportPasswordDialog(uri, replaceMode)
                } else {
                    Logger.e("ImportExportActivity", "Failed to import backup", e)
                    io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
                        this@ImportExportActivity, getString(R.string.import_export_import_failed_title),
                        getString(R.string.import_export_import_failed_message, e.message)
                    )
                }
            }
        }
    }

    /**
     * Show a password input dialog when the selected backup is encrypted.
     */
    private fun showImportPasswordDialog(uri: android.net.Uri, replaceMode: Boolean) {
        val passwordInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.import_export_backup_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(passwordInput)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_export_encrypted_backup_title)
            .setMessage(R.string.import_export_encrypted_backup_message)
            .setView(layout)
            .setPositiveButton(R.string.conn_edit_import) { _, _ ->
                val password = passwordInput.text.toString()
                if (password.isNotBlank()) {
                    importBackupWithPassword(uri, password, replaceMode)
                } else {
                    Toast.makeText(this, R.string.import_export_password_required, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Retry the backup restore with the supplied [password].
     */
    private fun importBackupWithPassword(uri: android.net.Uri, password: String, replaceMode: Boolean) {
        lifecycleScope.launch {
            try {
                val bm = backupManager ?: run {
                    android.widget.Toast.makeText(this@ImportExportActivity, getString(R.string.import_export_backup_initialising), android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                val result = bm.restoreBackup(uri, password, overwriteExisting = false, replaceMode = replaceMode)

                if (result.success) {
                    showImportSuccessDialog(result)
                    Logger.i("ImportExportActivity", "Imported encrypted backup successfully")
                } else {
                    throw Exception(result.message)
                }

            } catch (e: Exception) {
                Logger.e("ImportExportActivity", "Failed to import backup with password", e)
                io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
                    this@ImportExportActivity, getString(R.string.import_export_import_failed_title),
                    getString(R.string.import_export_import_failed_message, e.message)
                )
            }
        }
    }

    /**
     * Show a summary dialog listing what was restored from a successful import.
     */
    private fun showImportSuccessDialog(result: BackupManager.RestoreResult) {
        val message = buildString {
            append(getString(R.string.import_export_import_success_header))
            result.restoredItems.forEach { (type, count) ->
                if (count > 0) {
                    append(getString(R.string.import_export_item_count_line, count, type))
                }
            }
        }

        MaterialAlertDialogBuilder(this@ImportExportActivity)
            .setTitle(R.string.import_export_backup_imported_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * Launch the SSH config file picker.
     */
    private fun importSSHConfig() {
        importSSHConfigLauncher.launch(arrayOf("*/*"))
    }

    /**
     * Export all current connections to an OpenSSH config text file at [uri].
     * Passwords are never written — they live in the Android Keystore.
     */
    private fun exportSshConfigToUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val connections = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getAllConnectionsList()
                }
                val groups = withContext(Dispatchers.IO) {
                    try { app.database.connectionGroupDao().getAllGroups().first() } catch (_: Exception) { emptyList() }
                }
                val text = io.github.tabssh.ssh.config.SSHConfigExporter.export(connections, groups)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw java.io.IOException(getString(R.string.import_export_could_not_open_output))
                }
                Toast.makeText(
                    this@ImportExportActivity,
                    getString(R.string.import_export_exported_connections, connections.size),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Logger.e("ImportExportActivity", "SSH config export failed", e)
                Toast.makeText(
                    this@ImportExportActivity,
                    getString(R.string.import_export_export_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Build the OpenSSH config export and show it as a themed, copyable text
     * block instead of a file. Passwords are never written — they live in
     * the Android Keystore.
     */
    private fun showSshConfigExportText() {
        lifecycleScope.launch {
            try {
                val connections = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getAllConnectionsList()
                }
                val groups = withContext(Dispatchers.IO) {
                    try { app.database.connectionGroupDao().getAllGroups().first() } catch (_: Exception) { emptyList() }
                }
                val text = io.github.tabssh.ssh.config.SSHConfigExporter.export(connections, groups)
                io.github.tabssh.ui.dialogs.TextExportDialog.show(
                    this@ImportExportActivity, getString(R.string.import_export_export_ssh_config_title), text
                )
            } catch (e: Exception) {
                Logger.e("ImportExportActivity", "SSH config export (text) failed", e)
                Toast.makeText(
                    this@ImportExportActivity,
                    getString(R.string.import_export_export_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Parse a bulk import file (CSV / JSON / PuTTY .reg / Terraform) and show
     * a preview/confirm dialog before inserting into the database.
     */
    private fun bulkImportFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    Logger.e("ImportExportActivity", "Bulk import read failed", e)
                    null
                }
            }
            if (text == null) {
                Toast.makeText(
                    this@ImportExportActivity,
                    getString(R.string.import_export_bulk_import_failed, getString(R.string.import_export_could_not_open_file)),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            bulkImportText(text)
        }
    }

    /** Parse bulk-import [text] (CSV / JSON / PuTTY .reg / Terraform), from a file or pasted directly. */
    private fun bulkImportText(text: String) {
        lifecycleScope.launch {
            try {
                val result = io.github.tabssh.ssh.config.BulkImportParser.parse(text)

                if (result.hosts.isEmpty()) {
                    val msg = buildString {
                        append(getString(R.string.import_export_no_connections_detected, result.format.name))
                        if (result.warnings.isNotEmpty()) {
                            append("\n\n")
                            append(result.warnings.joinToString("\n"))
                        }
                    }
                    MaterialAlertDialogBuilder(this@ImportExportActivity)
                        .setTitle(R.string.import_export_bulk_import_title)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@launch
                }
                showBulkImportPreviewDialog(result)
            } catch (e: Exception) {
                Logger.e("ImportExportActivity", "Bulk import failed", e)
                Toast.makeText(
                    this@ImportExportActivity,
                    getString(R.string.import_export_bulk_import_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showBulkImportPreviewDialog(result: io.github.tabssh.ssh.config.BulkImportParser.ParseResult) {
        val sample = result.hosts.take(20).joinToString("\n") { p ->
            val auth = p.authType?.let { getString(R.string.import_export_bulk_auth_suffix, it) }.orEmpty()
            val grp = p.groupName?.let { getString(R.string.import_export_bulk_group_suffix, it) }.orEmpty()
            getString(
                R.string.import_export_bulk_preview_line,
                p.name, p.username ?: "?", p.host, p.port, auth, grp
            )
        }
        val more = if (result.hosts.size > 20) {
            getString(R.string.import_export_bulk_more_hosts, result.hosts.size - 20)
        } else ""
        val warn = if (result.warnings.isNotEmpty()) {
            getString(R.string.import_export_bulk_warnings_header) +
                result.warnings.take(8).joinToString("\n") { getString(R.string.import_export_bulk_warning_line, it) }
        } else ""

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.import_export_bulk_import_format_title, result.format.name))
            .setMessage(getString(R.string.import_export_bulk_found_connections, result.hosts.size, sample, more, warn))
            .setPositiveButton(R.string.conn_edit_import) { _, _ ->
                val profiles = result.hosts.map { it.toConnectionProfile() }
                importSSHConfigProfiles(profiles)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Read an OpenSSH config file from [uri] and show a summary/confirm dialog.
     */
    private fun importSSHConfigFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            val configContent = withContext(Dispatchers.IO) {
                // Chain `?.bufferedReader()?.use {}` so the underlying SAF
                // ParcelFileDescriptor closes even if the reader exhausts
                // the stream — assigning to a `val` first leaked the fd.
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    Logger.e("ImportExportActivity", "SSH config read failed", e)
                    null
                }
            }
            if (configContent == null) {
                Toast.makeText(
                    this@ImportExportActivity,
                    getString(R.string.import_export_ssh_config_import_failed, getString(R.string.import_export_could_not_open_file)),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            importSSHConfigText(configContent)
        }
    }

    /** Parse OpenSSH config [configContent] (from a file or pasted directly) and show a summary/confirm dialog. */
    private fun importSSHConfigText(configContent: String) {
        lifecycleScope.launch {
            try {
                val profiles = withContext(Dispatchers.IO) {
                    val raw = io.github.tabssh.ssh.config.SSHConfigParser().parseConfig(configContent)

                    // Attempt to resolve each profile's IdentityFile path to a
                    // stored key by matching the path's basename against the
                    // key's alias (e.g. `~/.ssh/id_ed25519` → alias `id_ed25519`).
                    // Falls back to matching by display name. If a match is found
                    // the keyId is populated and auth works immediately after import.
                    raw.map { profile ->
                        if (profile.keyId != null) return@map profile
                        if (profile.authType != AuthType.PUBLIC_KEY.name) return@map profile
                        val identityPath = profile.advancedSettings?.let { raw ->
                            try {
                                org.json.JSONObject(raw).optString("identityFileStr")
                                    .takeIf { it.isNotBlank() }
                            } catch (_: Exception) { null }
                        } ?: return@map profile

                        // Strip directory and ~ expansion; match on bare filename.
                        val basename = identityPath
                            .substringAfterLast('/')
                            .substringAfterLast('\\')
                            .removeSuffix(".pub")

                        val resolvedKey = app.database.keyDao().getKeyByAlias(basename)
                            ?: app.database.keyDao().getKeyByName(basename)

                        if (resolvedKey != null) {
                            Logger.i(
                                "ImportExportActivity",
                                "Resolved IdentityFile '$basename' → key ${resolvedKey.keyId}"
                            )
                            profile.copy(keyId = resolvedKey.keyId)
                        } else {
                            profile
                        }
                    }
                }

                showSSHConfigImportDialog(profiles)

            } catch (e: Exception) {
                Logger.e("ImportExportActivity", "Failed to import SSH config", e)
                Toast.makeText(
                    this@ImportExportActivity,
                    getString(R.string.import_export_ssh_config_import_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Show a confirmation dialog listing detected hosts and groups before inserting.
     * Also warns when any host uses an IdentityFile that can't be resolved to a stored
     * key — the user must import the key manually after import.
     */
    private fun showSSHConfigImportDialog(profiles: List<io.github.tabssh.storage.database.entities.ConnectionProfile>) {
        val groups = profiles.mapNotNull { it.groupId }.filter { it.isNotBlank() }.toSet()

        // Detect profiles where IdentityFile was parsed but no key is stored yet.
        // These will have authType=PUBLIC_KEY and keyId=null; at connect time key
        // auth is silently skipped and falls back to keyboard-interactive, which
        // fails on key-only servers with a confusing error message.
        val unresolvedKeyProfiles = profiles.filter { p ->
            p.keyId == null &&
            p.authType == AuthType.PUBLIC_KEY.name &&
            p.advancedSettings?.let { raw ->
                try { org.json.JSONObject(raw).optString("identityFileStr").isNotBlank() }
                catch (_: Exception) { false }
            } == true
        }
        val unresolvedKeyPaths: Set<String> = unresolvedKeyProfiles.mapNotNull { p ->
            p.advancedSettings?.let { raw ->
                try { org.json.JSONObject(raw).optString("identityFileStr").takeIf { it.isNotBlank() } }
                catch (_: Exception) { null }
            }
        }.toSet()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_export_import_ssh_config_title)
            .setMessage(buildString {
                append(getString(R.string.import_export_found_hosts, profiles.size))
                if (groups.isNotEmpty()) {
                    append(getString(R.string.import_export_in_groups, groups.size))
                }
                append("\n\n")

                if (groups.isNotEmpty()) {
                    append(getString(R.string.import_export_groups_to_create))
                    groups.sorted().forEach { group ->
                        val count = profiles.count { it.groupId == group }
                        append(getString(R.string.import_export_group_count_line, group, count))
                    }
                    append("\n")
                }

                if (profiles.isNotEmpty()) {
                    append(getString(R.string.import_export_hosts_header))
                    val grouped = profiles.groupBy { it.groupId ?: getString(R.string.import_export_ungrouped) }
                    var shown = 0
                    grouped.forEach { (group, groupProfiles) ->
                        if (shown < 15) {
                            append(getString(R.string.import_export_group_header_line, group))
                            groupProfiles.take(5).forEach { profile ->
                                append(getString(R.string.import_export_host_line, profile.name))
                                shown++
                            }
                            if (groupProfiles.size > 5) {
                                append(getString(R.string.import_export_more_in_group, groupProfiles.size - 5))
                            }
                        }
                    }
                    if (profiles.size > 15) {
                        append(getString(R.string.import_export_more_hosts))
                    }
                }

                // Identity file warning — must be last so it stands out
                if (unresolvedKeyProfiles.isNotEmpty()) {
                    append(getString(R.string.import_export_unresolved_keys_warning, unresolvedKeyProfiles.size))
                    unresolvedKeyPaths.forEach { path -> append(getString(R.string.import_export_unresolved_key_path, path)) }
                    append(getString(R.string.import_export_unresolved_keys_hint))
                }
            })
            .setPositiveButton(R.string.conn_edit_import) { _, _ ->
                importSSHConfigProfiles(profiles, unresolvedKeyProfiles.isNotEmpty())
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()
    }

    /**
     * Insert parsed connection profiles into the database, creating groups as needed.
     * Deduplicates on (host, port, username) to avoid re-importing the same hosts.
     *
     * [hasUnresolvedKeys] — true when at least one profile's IdentityFile couldn't
     * be resolved to a stored key. A persistent Snackbar prompts the user to navigate
     * to the Identities tab after the import completes.
     */
    private fun importSSHConfigProfiles(
        profiles: List<io.github.tabssh.storage.database.entities.ConnectionProfile>,
        hasUnresolvedKeys: Boolean = false
    ) {
        lifecycleScope.launch {
            try {
                val groupDao = app.database.connectionGroupDao()

                val groupNames = profiles.mapNotNull { it.groupId }.filter { it.isNotBlank() }.toSet()
                Logger.d("ImportExportActivity", "Found ${groupNames.size} unique groups: $groupNames")

                val groupNameToId = mutableMapOf<String, String>()
                var groupsCreated = 0

                for (groupName in groupNames) {
                    val existingGroup = groupDao.getGroupByName(groupName)
                    if (existingGroup != null) {
                        groupNameToId[groupName] = existingGroup.id
                        Logger.d("ImportExportActivity", "Group '$groupName' already exists with ID: ${existingGroup.id}")
                    } else {
                        val newGroup = io.github.tabssh.storage.database.entities.ConnectionGroup(
                            name = groupName,
                            icon = "folder",
                            sortOrder = groupsCreated
                        )
                        groupDao.insertGroup(newGroup)
                        groupNameToId[groupName] = newGroup.id
                        groupsCreated++
                        Logger.d("ImportExportActivity", "Created new group '$groupName' with ID: ${newGroup.id}")
                    }
                }

                val existing = app.database.connectionDao().getAllConnectionsList()
                val existingTriples = existing.map { Triple(it.host, it.port, it.username) }.toHashSet()
                var connectionsImported = 0
                var connectionsSkipped = 0
                profiles.forEach { profile ->
                    val updatedProfile = if (profile.groupId != null && groupNameToId.containsKey(profile.groupId)) {
                        profile.copy(groupId = groupNameToId[profile.groupId])
                    } else {
                        profile
                    }
                    val triple = Triple(updatedProfile.host, updatedProfile.port, updatedProfile.username)
                    if (existingTriples.contains(triple)) {
                        connectionsSkipped++
                    } else {
                        app.database.connectionDao().insertConnection(updatedProfile)
                        // Catch in-batch duplicates too
                        existingTriples.add(triple)
                        connectionsImported++
                    }
                }

                val message = buildString {
                    append(getString(R.string.import_export_imported_connections, connectionsImported))
                    if (connectionsSkipped > 0) {
                        append(getString(R.string.import_export_skipped_connections, connectionsSkipped))
                    }
                    if (groupsCreated > 0) {
                        append(getString(R.string.import_export_created_groups, groupsCreated))
                    }
                }
                Toast.makeText(
                    this@ImportExportActivity,
                    message,
                    Toast.LENGTH_LONG
                ).show()

                // When IdentityFile entries were present but unresolvable, prompt
                // the user to go import their key now. Snackbar is indefinite so
                // they can act on it rather than race a timer.
                if (hasUnresolvedKeys) {
                    val rootView = window.decorView.findViewById<android.view.View>(android.R.id.content)
                    Snackbar.make(
                        rootView,
                        getString(R.string.import_export_need_key_snackbar),
                        Snackbar.LENGTH_INDEFINITE
                    ).setAction(R.string.import_export_identities_action) {
                        // Navigate to MainActivity and open the Identities tab (index 2).
                        val intent = Intent(this@ImportExportActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("start_tab", 2)
                        }
                        startActivity(intent)
                    }.show()
                }

            } catch (e: Exception) {
                Logger.e("ImportExportActivity", "Failed to save imported connections", e)
                Toast.makeText(
                    this@ImportExportActivity,
                    getString(R.string.import_export_save_connections_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Show the export options dialog (encrypted vs. unencrypted) before writing to [uri].
     */
    private fun exportBackupToUri(uri: android.net.Uri) {
        showExportOptionsDialog(uri)
    }

    /**
     * Let the user choose between an unencrypted and password-protected export.
     */
    private fun showExportOptionsDialog(uri: android.net.Uri) {
        val options = arrayOf(
            getString(R.string.import_export_option_unencrypted),
            getString(R.string.import_export_option_encrypted)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_export_options_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showUnencryptedExportWarning(uri)
                    1 -> showExportPasswordDialog(uri)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Hard type-to-confirm gate for an unencrypted backup.
     *
     * An unencrypted archive still contains every credential the app holds, so
     * the warning names exactly what is exposed and the export only proceeds
     * once the user has typed the confirmation word verbatim. A single tap is
     * deliberately not enough.
     */
    private fun showUnencryptedExportWarning(uri: android.net.Uri) {
        val confirmWord = getString(R.string.import_export_unencrypted_confirm_word)
        val confirmInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.import_export_unencrypted_confirm_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(confirmInput)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_export_unencrypted_warning_title)
            .setMessage(R.string.import_export_unencrypted_warning_message)
            .setView(layout)
            .setPositiveButton(R.string.import_export_export_without_encryption, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        // Bind the positive button after show() so a mistyped confirmation
        // leaves the dialog open instead of silently cancelling the export.
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (confirmInput.text.toString().trim() == confirmWord) {
                    dialog.dismiss()
                    performExport(uri, password = null, plaintextSecretsConfirmed = true)
                } else {
                    Toast.makeText(
                        this,
                        R.string.import_export_unencrypted_confirm_required,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        dialog.show()
    }

    /**
     * Collect a password (with confirmation) before kicking off an encrypted export.
     */
    private fun showExportPasswordDialog(uri: android.net.Uri) {
        val passwordInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.import_export_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val confirmInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.import_export_confirm_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(passwordInput)
            addView(confirmInput.apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
            })
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_export_encrypt_backup_title)
            .setMessage(R.string.import_export_encrypt_backup_message)
            .setView(layout)
            .setPositiveButton(R.string.import_export_export) { _, _ ->
                val password = passwordInput.text.toString()
                val confirm = confirmInput.text.toString()

                when {
                    password.isBlank() -> {
                        Toast.makeText(this, R.string.import_export_password_required, Toast.LENGTH_SHORT).show()
                    }
                    password != confirm -> {
                        Toast.makeText(this, R.string.sync_password_mismatch, Toast.LENGTH_SHORT).show()
                    }
                    password.length < 4 -> {
                        Toast.makeText(this, R.string.import_export_password_too_short, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        performExport(uri, password)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Write the backup to [uri] with the chosen encryption settings.
     *
     * @param plaintextSecretsConfirmed must be true when [password] is null —
     *   the user typed the confirmation word acknowledging that the archive
     *   exposes every stored credential in readable form.
     */
    private fun performExport(
        uri: android.net.Uri,
        password: String?,
        plaintextSecretsConfirmed: Boolean = false
    ) {
        lifecycleScope.launch {
            try {
                val bm = backupManager ?: run {
                    android.widget.Toast.makeText(this@ImportExportActivity, getString(R.string.import_export_backup_initialising), android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                val result = bm.createBackup(
                    outputUri = uri,
                    encryptBackup = password != null,
                    password = password,
                    plaintextSecretsConfirmed = plaintextSecretsConfirmed
                )

                if (result.success) {
                    val encryptedLabel = if (password != null) {
                        getString(R.string.import_export_label_encrypted)
                    } else {
                        getString(R.string.import_export_label_unencrypted)
                    }
                    Toast.makeText(
                        this@ImportExportActivity,
                        getString(R.string.import_export_backup_exported_toast, encryptedLabel),
                        Toast.LENGTH_LONG
                    ).show()

                    val message = buildString {
                        append(getString(R.string.import_export_export_verified))
                        if (password != null) {
                            append(getString(R.string.import_export_encrypted_with_password))
                        } else {
                            append(getString(R.string.import_export_unencrypted_no_protection))
                        }
                        result.metadata?.itemCounts?.let { items ->
                            if (items.isNotEmpty()) {
                                append(getString(R.string.import_export_exported_header))
                                items.forEach { (type, count) ->
                                    if (count > 0) {
                                        append(getString(R.string.import_export_item_count_line, count, type))
                                    }
                                }
                            }
                        }
                    }

                    MaterialAlertDialogBuilder(this@ImportExportActivity)
                        .setTitle(R.string.import_export_export_complete_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.ok, null)
                        .show()

                    Logger.i("ImportExportActivity", "Exported backup successfully")
                } else {
                    throw Exception(getString(R.string.import_export_export_failed, result.message))
                }

            } catch (e: Exception) {
                Logger.e("ImportExportActivity", "Failed to export backup", e)
                Toast.makeText(
                    this@ImportExportActivity,
                    getString(R.string.import_export_backup_export_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
                    this@ImportExportActivity, getString(R.string.import_export_export_failed_title),
                    getString(R.string.import_export_export_failed_message, e.message)
                )
            }
        }
    }
}
