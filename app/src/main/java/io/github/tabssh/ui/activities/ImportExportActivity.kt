package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
class ImportExportActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var backupManager: BackupManager

    // SAF launcher — restore from a ZIP backup
    private val importConnectionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importBackupFromUri(it) }
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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // BackupManager's constructor calls SyncEncryptor() which seeds BouncyCastle's
        // DRBG — a potentially blocking operation. Initialise on IO so onCreate returns
        // immediately; all usages are inside coroutines triggered by user actions.
        lifecycleScope.launch(Dispatchers.IO) {
            backupManager = BackupManager(this@ImportExportActivity)
        }

        // Wire card click listeners
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_import_ssh)
            .setOnClickListener { importSSHConfigLauncher.launch(arrayOf("*/*")) }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_export_ssh)
            .setOnClickListener {
                exportSshConfigLauncher.launch("ssh_config_${System.currentTimeMillis() / 1000}.txt")
            }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_bulk_import)
            .setOnClickListener { bulkImportLauncher.launch(arrayOf("*/*")) }

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

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Attempt to restore a backup from [uri] without a password first.
     * Falls back to the password dialog when the backup appears to be encrypted.
     */
    private fun importBackupFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val result = backupManager.restoreBackup(uri, password = null, overwriteExisting = false)

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
                    showImportPasswordDialog(uri)
                } else {
                    Logger.e("ImportExportActivity", "Failed to import backup", e)
                    io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
                        this@ImportExportActivity, "Import Failed",
                        "Failed to import backup:\n${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Show a password input dialog when the selected backup is encrypted.
     */
    private fun showImportPasswordDialog(uri: android.net.Uri) {
        val passwordInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Enter backup password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(passwordInput)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Encrypted Backup")
            .setMessage("This backup is encrypted. Enter the password to decrypt it.")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val password = passwordInput.text.toString()
                if (password.isNotBlank()) {
                    importBackupWithPassword(uri, password)
                } else {
                    Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Retry the backup restore with the supplied [password].
     */
    private fun importBackupWithPassword(uri: android.net.Uri, password: String) {
        lifecycleScope.launch {
            try {
                val result = backupManager.restoreBackup(uri, password, overwriteExisting = false)

                if (result.success) {
                    showImportSuccessDialog(result)
                    Logger.i("ImportExportActivity", "Imported encrypted backup successfully")
                } else {
                    throw Exception(result.message)
                }

            } catch (e: Exception) {
                Logger.e("ImportExportActivity", "Failed to import backup with password", e)
                io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
                    this@ImportExportActivity, "Import Failed",
                    "Failed to import backup:\n${e.message}"
                )
            }
        }
    }

    /**
     * Show a summary dialog listing what was restored from a successful import.
     */
    private fun showImportSuccessDialog(result: BackupManager.RestoreResult) {
        val message = buildString {
            append("Import successful!\n\n")
            append("Imported:\n")
            result.restoredItems.forEach { (type, count) ->
                if (count > 0) {
                    append("  • $count $type\n")
                }
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this@ImportExportActivity)
            .setTitle("Backup Imported")
            .setMessage(message)
            .setPositiveButton("OK", null)
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
                        ?: throw java.io.IOException("Could not open output stream")
                }
                Toast.makeText(
                    this@ImportExportActivity,
                    "Exported ${connections.size} connections",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Logger.e("ImportExportActivity", "SSH config export failed", e)
                Toast.makeText(
                    this@ImportExportActivity,
                    "Export failed: ${e.message}",
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
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: throw Exception("Could not open file")

                val result = io.github.tabssh.ssh.config.BulkImportParser.parse(text)

                if (result.hosts.isEmpty()) {
                    val msg = buildString {
                        append("No connections detected (${result.format.name}).")
                        if (result.warnings.isNotEmpty()) {
                            append("\n\n")
                            append(result.warnings.joinToString("\n"))
                        }
                    }
                    androidx.appcompat.app.AlertDialog.Builder(this@ImportExportActivity)
                        .setTitle("Bulk Import")
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
                    "Bulk import failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showBulkImportPreviewDialog(result: io.github.tabssh.ssh.config.BulkImportParser.ParseResult) {
        val sample = result.hosts.take(20).joinToString("\n") { p ->
            val auth = p.authType?.let { " · $it" }.orEmpty()
            val grp = p.groupName?.let { " [${'$'}it]" }.orEmpty()
            "• ${p.name} → ${p.username ?: "?"}@${p.host}:${p.port}$auth$grp"
        }
        val more = if (result.hosts.size > 20) "\n… and ${result.hosts.size - 20} more" else ""
        val warn = if (result.warnings.isNotEmpty()) {
            "\n\nWarnings:\n" + result.warnings.take(8).joinToString("\n") { "  - $it" }
        } else ""

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bulk Import: ${result.format.name}")
            .setMessage("Found ${result.hosts.size} connection(s).\n\n$sample$more$warn")
            .setPositiveButton("Import") { _, _ ->
                val profiles = result.hosts.map { it.toConnectionProfile() }
                importSSHConfigProfiles(profiles)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Read an OpenSSH config file from [uri] and show a summary/confirm dialog.
     */
    private fun importSSHConfigFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val profiles = withContext(Dispatchers.IO) {
                    val inputStream = contentResolver.openInputStream(uri)
                        ?: throw Exception("Could not open file")
                    val configContent = inputStream.bufferedReader().use { it.readText() }
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
                    "Failed to import SSH config: ${e.message}",
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

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Import SSH Config")
            .setMessage(buildString {
                append("Found ${profiles.size} host(s)")
                if (groups.isNotEmpty()) {
                    append(" in ${groups.size} group(s)")
                }
                append("\n\n")

                if (groups.isNotEmpty()) {
                    append("Groups to create:\n")
                    groups.sorted().forEach { group ->
                        val count = profiles.count { it.groupId == group }
                        append("  [$group] ($count hosts)\n")
                    }
                    append("\n")
                }

                if (profiles.isNotEmpty()) {
                    append("Hosts:\n")
                    val grouped = profiles.groupBy { it.groupId ?: "Ungrouped" }
                    var shown = 0
                    grouped.forEach { (group, groupProfiles) ->
                        if (shown < 15) {
                            append("[$group]\n")
                            groupProfiles.take(5).forEach { profile ->
                                append("  • ${profile.name}\n")
                                shown++
                            }
                            if (groupProfiles.size > 5) {
                                append("    ... and ${groupProfiles.size - 5} more\n")
                            }
                        }
                    }
                    if (profiles.size > 15) {
                        append("\n... and more hosts\n")
                    }
                }

                // Identity file warning — must be last so it stands out
                if (unresolvedKeyProfiles.isNotEmpty()) {
                    append("\n⚠ ${unresolvedKeyProfiles.size} host(s) use SSH key auth but the key")
                    append(" can't be resolved on this device:\n")
                    unresolvedKeyPaths.forEach { path -> append("  $path\n") }
                    append("\nAfter import: go to the Identities tab, import your")
                    append(" private key, then edit each connection and select it.")
                }
            })
            .setPositiveButton("Import") { _, _ ->
                importSSHConfigProfiles(profiles, unresolvedKeyProfiles.isNotEmpty())
            }
            .setNegativeButton("Cancel", null)
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
                    append("✓ Imported $connectionsImported connection(s)")
                    if (connectionsSkipped > 0) {
                        append("\n• Skipped $connectionsSkipped already-existing host(s)")
                    }
                    if (groupsCreated > 0) {
                        append("\n✓ Created $groupsCreated new group(s)")
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
                        "Some connections need an SSH key — go import it now",
                        Snackbar.LENGTH_INDEFINITE
                    ).setAction("Identities") {
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
                    "Failed to save connections: ${e.message}",
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
            "Export without encryption",
            "Export with password protection"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Export Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showUnencryptedExportWarning(uri)
                    1 -> showExportPasswordDialog(uri)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Warn the user that an unencrypted backup is readable by anyone with access
     * to the file, then require explicit confirmation before proceeding.
     */
    private fun showUnencryptedExportWarning(uri: android.net.Uri) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Unencrypted Backup")
            .setMessage(
                "This backup will be saved as plain, unencrypted JSON.\n\n" +
                "Anyone who can read the file will see all your host addresses, " +
                "usernames, ports, and configuration details.\n\n" +
                "Saved passwords and private keys are always excluded from " +
                "unencrypted backups to reduce risk, but the remaining data " +
                "is still sensitive.\n\n" +
                "For full protection — including the ability to restore passwords " +
                "and keys — use an encrypted backup with a strong password."
            )
            .setPositiveButton("Export Without Encryption") { _, _ ->
                performExport(uri, includePasswords = false, password = null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Collect a password (with confirmation) and an optional "include passwords" flag
     * before kicking off an encrypted export.
     */
    private fun showExportPasswordDialog(uri: android.net.Uri) {
        val passwordInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Enter password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val confirmInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Confirm password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val includePasswordsCheckbox = android.widget.CheckBox(this).apply {
            text = "Include saved passwords (encrypted)"
            isChecked = false
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
            addView(includePasswordsCheckbox.apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
            })
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Encrypt Backup")
            .setMessage("Enter a password to encrypt your backup.")
            .setView(layout)
            .setPositiveButton("Export") { _, _ ->
                val password = passwordInput.text.toString()
                val confirm = confirmInput.text.toString()
                val includePasswords = includePasswordsCheckbox.isChecked

                when {
                    password.isBlank() -> {
                        Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show()
                    }
                    password != confirm -> {
                        Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                    }
                    password.length < 4 -> {
                        Toast.makeText(this, "Password too short (minimum 4 characters)", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        performExport(uri, includePasswords, password)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Write the backup ZIP to [uri] with the chosen encryption settings.
     */
    private fun performExport(uri: android.net.Uri, includePasswords: Boolean, password: String?) {
        lifecycleScope.launch {
            try {
                val result = backupManager.createBackup(
                    outputUri = uri,
                    includePasswords = includePasswords,
                    encryptBackup = password != null,
                    password = password
                )

                if (result.success) {
                    val encryptedLabel = if (password != null) " (encrypted)" else " (unencrypted)"
                    Toast.makeText(
                        this@ImportExportActivity,
                        "Backup exported successfully$encryptedLabel",
                        Toast.LENGTH_LONG
                    ).show()

                    val message = buildString {
                        append("Backup exported and verified successfully!")
                        if (password != null) {
                            append("\n\n🔐 Encrypted with password")
                        } else {
                            append("\n\n⚠️ Unencrypted — no password protection")
                        }
                        result.metadata?.itemCounts?.let { items ->
                            if (items.isNotEmpty()) {
                                append("\n\nExported:\n")
                                items.forEach { (type, count) ->
                                    if (count > 0) {
                                        append("  • $count $type\n")
                                    }
                                }
                            }
                        }
                    }

                    androidx.appcompat.app.AlertDialog.Builder(this@ImportExportActivity)
                        .setTitle("Export Complete")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()

                    Logger.i("ImportExportActivity", "Exported backup successfully")
                } else {
                    throw Exception("Export failed: ${result.message}")
                }

            } catch (e: Exception) {
                Logger.e("ImportExportActivity", "Failed to export backup", e)
                Toast.makeText(
                    this@ImportExportActivity,
                    "Backup export failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
                    this@ImportExportActivity, "Export Failed",
                    "Failed to export backup:\n${e.message}"
                )
            }
        }
    }
}
