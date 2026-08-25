package io.github.tabssh.ui.fragments

import io.github.tabssh.sync.tombstone.TombstoneRecorder
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.withTransaction
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.ui.dialogs.DialogFields
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.keys.GenerateResult
import io.github.tabssh.crypto.keys.ImportResult
import io.github.tabssh.crypto.keys.KeyImportErrorType
import io.github.tabssh.crypto.keys.KeyType
import io.github.tabssh.crypto.keys.toUserMessage
import io.github.tabssh.crypto.SSHKeyGenerator
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.StoredKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import io.github.tabssh.ui.adapters.StoredKeyAdapter
import io.github.tabssh.utils.ThrowableMapper
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.showError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/** Auth tab — Keys sub-tab: raw SSH private keys used by host identities. */
class AuthKeysFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var keyAdapter: StoredKeyAdapter

    // Fragment-scoped, so an in-flight SSH connect from installKeyOnServer()
    // doesn't outlive the fragment's view.
    private val installKeyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── SAF launchers — must be declared as field initializers (before onStart) ──

    /** Opens a file picker for SSH key import. */
    private val importKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importKeyFromFile(it) } }

    /** Opens a file picker for OpenSSH certificate attachment. */
    private val attachCertLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val key = pendingCertKey ?: return@registerForActivityResult
        pendingCertKey = null
        uri ?: return@registerForActivityResult
        // Capture context on the Main thread before switching to IO
        val ctx = context ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = ctx.contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    if (validateCert(text)) setKeyCert(key, text, getString(R.string.identity_cert_attached_toast))
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to read cert file", e)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    Toast.makeText(requireContext(), getString(R.string.identity_read_cert_failed_fmt, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Opens a SAF file-save dialog so the user can choose where to write
     * the exported private key.  [pendingExportContent] must be set before
     * calling launch().
     */
    private val exportKeyLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val content = pendingExportContent ?: return@registerForActivityResult
        pendingExportContent = null
        uri ?: return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    Toast.makeText(requireContext(), getString(R.string.identity_private_key_exported_toast), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to write private key export", e)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    Toast.makeText(requireContext(), getString(R.string.identity_export_failed_fmt, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── State bridging SAF results back to dialog callbacks ──────────────────

    /** The key currently awaiting a certificate file from [attachCertLauncher]. */
    private var pendingCertKey: StoredKey? = null

    /** Staged private key PEM content waiting for the SAF save dialog from [exportKeyLauncher]. */
    private var pendingExportContent: String? = null

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_auth_keys, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = tabSSHApp

        setupSshKeysSection(view)
        observeData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        installKeyScope.cancel()
    }

    // ─── SSH Keys ────────────────────────────────────────────────────────────

    private fun setupSshKeysSection(view: View) {
        keyAdapter = StoredKeyAdapter { key -> showSshKeyOptionsMenu(key) }
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_ssh_keys)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = keyAdapter

        keyAdapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() { updateKeysEmptyState(view) }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { updateKeysEmptyState(view) }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { updateKeysEmptyState(view) }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { updateKeysEmptyState(view) }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { updateKeysEmptyState(view) }
        })
        updateKeysEmptyState(view)

        view.findViewById<MaterialButton>(R.id.btn_add_ssh_key).setOnClickListener {
            showSshKeyAddMenu()
        }
        view.findViewById<MaterialButton>(R.id.button_ssh_keys_empty_cta).setOnClickListener {
            showSshKeyAddMenu()
        }
    }

    private fun updateKeysEmptyState(view: View) {
        val empty = keyAdapter.itemCount == 0
        view.findViewById<View>(R.id.recycler_ssh_keys).visibility = if (empty) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.text_ssh_keys_empty).visibility = if (empty) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.button_ssh_keys_empty_cta).visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ─── Data observation ────────────────────────────────────────────────────

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.database.keyDao().getAllKeys().collect { list ->
                        keyAdapter.submitList(list)
                        Logger.d(TAG, "Loaded ${list.size} SSH keys")
                    }
                }
            }
        }
    }

    // ─── SSH Key dialogs ─────────────────────────────────────────────────────

    private fun showSshKeyAddMenu() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.identity_add_ssh_key_title))
            .setItems(arrayOf(
                getString(R.string.identity_menu_import_from_file),
                getString(R.string.identity_menu_paste_key),
                getString(R.string.identity_menu_generate_key)
            )) { _, which ->
                when (which) {
                    0 -> importKeyLauncher.launch(arrayOf("*/*"))
                    1 -> showKeyPasteDialog()
                    2 -> showKeyGenerateDialog()
                }
            }
            .show()
    }

    private fun showSshKeyOptionsMenu(key: StoredKey) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(key.getDisplayName())
            .setItems(arrayOf(
                getString(R.string.identity_menu_details_more),
                getString(R.string.identity_menu_delete_key)
            )) { _, which ->
                when (which) {
                    0 -> showKeyDetails(key)
                    1 -> confirmDeleteSshKey(key)
                }
            }
            .show()
    }

    private fun showKeyDetails(key: StoredKey) {
        val certInfo = key.certificate?.let {
            val firstField = it.trim().substringBefore(' ')
            getString(R.string.identity_key_cert_attached_fmt, firstField)
        } ?: getString(R.string.identity_key_cert_none)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(key.name)
            .setMessage(
                getString(R.string.sftp_props_type_fmt, key.keyType) + "\n" +
                getString(R.string.identity_key_fingerprint_fmt, key.fingerprint) + "\n" +
                getString(R.string.identity_key_created_fmt, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(key.createdAt))) + "\n" +
                (if (!key.comment.isNullOrEmpty()) getString(R.string.identity_key_comment_fmt, key.comment) + "\n" else "") +
                certInfo
            )
            .setPositiveButton(getString(R.string.ok), null)
            .setNeutralButton(getString(R.string.identity_more_button)) { _, _ -> showMoreActionsDialog(key) }
            .setNegativeButton(getString(R.string.delete)) { _, _ -> confirmDeleteSshKey(key) }
            .show()
    }

    private fun showMoreActionsDialog(key: StoredKey) {
        val copyPublicKey = getString(R.string.identity_menu_copy_public_key)
        val installOnServer = getString(R.string.identity_menu_install_on_server)
        val exportPrivateKey = getString(R.string.identity_menu_export_private_key)
        val rename = getString(R.string.rename_file)
        val attachCertPaste = getString(R.string.identity_menu_attach_cert_paste)
        val attachCertFile = getString(R.string.identity_menu_attach_cert_file)
        val removeCertificate = getString(R.string.identity_menu_remove_certificate)
        val items = mutableListOf(
            copyPublicKey,
            installOnServer,
            exportPrivateKey,
            rename,
            attachCertPaste,
            attachCertFile
        )
        if (key.certificate != null) items += removeCertificate
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(key.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    copyPublicKey -> copyPublicKeyToClipboard(key)
                    installOnServer -> showInstallOnServerDialog(key)
                    exportPrivateKey -> showExportPrivateKeyDialog(key)
                    rename -> showRenameKeyDialog(key)
                    attachCertPaste -> showPasteCertDialog(key)
                    attachCertFile -> { pendingCertKey = key; attachCertLauncher.launch(arrayOf("*/*")) }
                    removeCertificate -> setKeyCert(key, null, getString(R.string.identity_certificate_removed_toast))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showInstallOnServerDialog(key: StoredKey) {
        lifecycleScope.launch(Dispatchers.IO) {
            val sshConnections = app.database.connectionDao().getAllConnectionsList()
                .filter { it.protocol == "ssh" }
            if (sshConnections.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.identity_no_ssh_connections_saved_toast), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val names = sshConnections
                .map { "${it.name} (${it.username}@${it.host}:${it.port})" }
                .toTypedArray()
            withContext(Dispatchers.Main) {
                var selectedIdx = 0
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.identity_install_on_server_title_fmt, key.name))
                    .setSingleChoiceItems(names, 0) { _, i -> selectedIdx = i }
                    .setPositiveButton(getString(R.string.identity_install_button)) { _, _ ->
                        installKeyOnServer(key, sshConnections[selectedIdx])
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }

    private fun installKeyOnServer(key: StoredKey, profile: ConnectionProfile) {
        lifecycleScope.launch(Dispatchers.IO) {
            val progressDialog = withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.identity_installing_key_title))
                    .setMessage(getString(R.string.connecting_to, profile.host))
                    .setCancelable(false)
                    .show()
            }
            val scope = installKeyScope
            var connection: SSHConnection? = null
            try {
                val pubKey = app.keyStorage.getPublicKeyText(key.keyId)
                    ?: throw Exception("Could not read public key")
                connection = SSHConnection(profile, scope, app)
                connection.hostKeyChangedCallback = app.sshSessionManager.hostKeyChangedCallback
                connection.newHostKeyCallback = app.sshSessionManager.newHostKeyCallback
                val connected = connection.connect()
                if (!connected) throw Exception("Authentication failed")
                // Shell-safe single-quote escaping for the public key literal.
                val safeKey = pubKey.trim().replace("'", "'\\''")
                // Idempotent: check for the exact key line before appending.
                val cmd = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                    "if grep -qxF '$safeKey' ~/.ssh/authorized_keys 2>/dev/null; " +
                    "then printf 'already_present\\n'; " +
                    "else printf '%s\\n' '$safeKey' >> ~/.ssh/authorized_keys && " +
                    "chmod 600 ~/.ssh/authorized_keys && printf 'installed\\n'; fi"
                val output = connection.executeCommand(cmd).trim()
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    val msg = when {
                        output.contains("already_present") ->
                            getString(R.string.identity_key_already_installed_fmt, profile.host)
                        output.contains("installed") ->
                            getString(R.string.identity_key_installed_fmt, profile.host)
                        else ->
                            getString(R.string.identity_key_installation_completed_fmt, profile.host)
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "installKeyOnServer failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    // Chicken-and-egg bootstrap case: the connection's own
                    // configured auth is this same not-yet-installed key, so
                    // "Authentication failed" here can never resolve itself —
                    // give an actionable message instead of a generic one.
                    val message = if (e.message?.contains("Authentication failed") == true &&
                        profile.keyId == key.keyId
                    ) {
                        getString(R.string.identity_install_key_bootstrap_failed_fmt, key.name, profile.host)
                    } else {
                        getString(R.string.identity_install_key_failed_fmt, e.message)
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private fun copyPublicKeyToClipboard(key: StoredKey) {
        lifecycleScope.launch(Dispatchers.IO) {
            val text = app.keyStorage.getPublicKeyText(key.keyId)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (text == null) {
                    Toast.makeText(requireContext(), getString(R.string.identity_read_public_key_failed_toast), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                io.github.tabssh.utils.ClipboardHelper.copy(requireContext(), getString(R.string.identity_public_key_clip_label), text, sensitive = false)
                Toast.makeText(requireContext(), getString(R.string.identity_public_key_copied_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showExportPrivateKeyDialog(key: StoredKey) {
        val form = DialogFields.form(requireContext())
        val passphraseEdit = DialogFields.addSecret(
            form, getString(R.string.identity_export_passphrase_hint)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.identity_export_private_key_title))
            .setMessage(getString(R.string.identity_export_passphrase_message))
            .setView(form.root)
            .setPositiveButton(getString(R.string.menu_export)) { _, _ ->
                val passphrase = passphraseEdit.text.toString().takeIf { it.isNotEmpty() }
                triggerPrivateKeyExport(key, passphrase)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun triggerPrivateKeyExport(key: StoredKey, passphrase: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val pem = try {
                val privateKey = app.keyStorage.retrievePrivateKey(key.keyId)
                    ?: throw Exception("Could not read private key from secure storage")
                val publicKey = app.keyStorage.getPublicKeyFromPrivate(privateKey)
                val comment = key.comment?.takeIf { it.isNotBlank() } ?: key.name
                SSHKeyGenerator.exportOpenSSHPrivateKey(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    comment = comment,
                    passphrase = passphrase?.takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                Logger.e(TAG, "Private key export failed", e)
                null
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (pem == null) {
                    Toast.makeText(requireContext(), getString(R.string.identity_read_key_export_failed_toast), Toast.LENGTH_LONG).show()
                    return@withContext
                }
                pendingExportContent = pem
                // Suggested filename: key name sanitized to a safe identifier.
                val safeName = key.name
                    .lowercase()
                    .replace(Regex("[^a-z0-9._-]"), "_")
                    .trimStart('_')
                    .ifBlank { "id_${key.keyType.lowercase()}" }
                exportKeyLauncher.launch(safeName)
            }
        }
    }

    /** Show a paste dialog pre-filled with clipboard if it looks like a cert. */
    private fun showPasteCertDialog(key: StoredKey) {
        val form = DialogFields.form(requireContext())
        val edit = DialogFields.addMultiline(
            form, getString(R.string.identity_cert_paste_hint),
            minLines = 4, maxLines = 8, monospace = true
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.identity_attach_cert_title))
            .setView(form.root)
            .setPositiveButton(getString(R.string.identity_attach_button)) { _, _ ->
                val cert = edit.text.toString().trim()
                if (validateCert(cert)) setKeyCert(key, cert, getString(R.string.identity_cert_attached_toast))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun validateCert(cert: String): Boolean {
        if (!cert.contains("-cert-v01@openssh.com")) {
            showError(
                getString(R.string.identity_cert_invalid_message),
                getString(R.string.identity_cert_invalid_title)
            )
            return false
        }
        return true
    }

    private fun setKeyCert(key: StoredKey, cert: String?, toastMsg: String) {
        lifecycleScope.launch {
            try {
                app.database.keyDao().updateKey(key.copy(certificate = cert))
                Toast.makeText(requireContext(), toastMsg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(requireContext(), TAG, e, "Failed to update certificate")
                showError(getString(R.string.identity_update_cert_failed_fmt, mapped.message), getString(R.string.status_error), copyText = mapped.technicalDetail)
            }
        }
    }

    private fun showRenameKeyDialog(key: StoredKey) {
        val form = DialogFields.form(requireContext())
        val edit = DialogFields.addText(
            form, getString(R.string.identity_rename_key_hint), initial = key.name
        )
        edit.selectAll()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.identity_rename_key_title))
            .setView(form.root)
            .setPositiveButton(getString(R.string.rename_file)) { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isNotBlank() && newName != key.name) renameKey(key, newName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun renameKey(key: StoredKey, newName: String) {
        lifecycleScope.launch {
            try {
                app.database.keyDao().updateKey(key.copy(name = newName))
                Toast.makeText(requireContext(), getString(R.string.identity_key_renamed_toast_fmt, newName), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(requireContext(), TAG, e, "Failed to rename key")
                showError(getString(R.string.identity_rename_key_failed_fmt, mapped.message), getString(R.string.identity_rename_error_title), copyText = mapped.technicalDetail)
            }
        }
    }

    private fun showKeyPasteDialog() {
        val form = DialogFields.form(requireContext())
        val edit = DialogFields.addMultiline(
            form, getString(R.string.paste_ssh_key_hint),
            minLines = 10, maxLines = 20, monospace = true
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.conn_edit_paste_key_title))
            .setView(form.root)
            .setPositiveButton(getString(R.string.next)) { _, _ ->
                val content = edit.text.toString()
                if (content.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val preview = app.keyStorage.previewKey(content)
                        val defaultName = preview.comment.takeIf { it.isNotBlank() } ?: getString(R.string.identity_default_pasted_key_name)
                        val defaultAlias = if (preview.keyType != null) {
                            app.keyStorage.generateDefaultAlias(preview.keyType)
                        } else "pasted_key"
                        withContext(Dispatchers.Main) {
                            promptForKeyNameAndAlias(defaultName, defaultAlias) { name, alias ->
                                importKeyContent(content, name, alias)
                            }
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showKeyGenerateDialog() {
        // Algorithm/key-size identifiers — fixed technical terms, not localizable UI copy.
        val keyTypes = arrayOf("RSA 2048", "RSA 4096", "ECDSA P-256", "ECDSA P-384", "Ed25519")
        // Default to Ed25519
        var selectedType = 4
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.identity_generate_key_title))
            .setSingleChoiceItems(keyTypes, selectedType) { _, which -> selectedType = which }
            .setPositiveButton(getString(R.string.next)) { _, _ ->
                val nameForm = DialogFields.form(requireContext())
                val nameEdit = DialogFields.addText(
                    nameForm, getString(R.string.identity_key_generate_name_hint)
                )
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.identity_key_name_title))
                    .setView(nameForm.root)
                    .setPositiveButton(getString(R.string.conn_edit_generate)) { _, _ ->
                        val keyName = nameEdit.text.toString().trim().ifBlank { getString(R.string.identity_default_generated_key_name) }
                        val (type, size) = when (selectedType) {
                            0 -> KeyType.RSA to 2048
                            1 -> KeyType.RSA to 4096
                            2 -> KeyType.ECDSA to 256
                            3 -> KeyType.ECDSA to 384
                            else -> KeyType.ED25519 to 256
                        }
                        generateKey(type, size, keyName)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun importKeyFromFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = requireContext().contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: return@launch
                val display = resolveDisplayName(uri) ?: uri.lastPathSegment ?: getString(R.string.identity_default_imported_key_name)
                val filenameBase = extractKeyNameFromFilename(display)

                // Quick parse to get comment + key type for smart dialog defaults.
                val preview = app.keyStorage.previewKey(content)

                // Name default: key comment if non-empty, else filename-derived.
                val defaultName = preview.comment.takeIf { it.isNotBlank() } ?: filenameBase

                // Alias default: SSH convention name (id_ed25519, etc.) with
                // collision suffix if needed. Fall back to filename stem if type
                // not determinable.
                val defaultAlias = if (preview.keyType != null) {
                    app.keyStorage.generateDefaultAlias(preview.keyType)
                } else {
                    filenameBase
                }

                withContext(Dispatchers.Main) {
                    promptForKeyNameAndAlias(defaultName, defaultAlias) { name, alias ->
                        importKeyContent(content, name, alias)
                    }
                }
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(requireContext(), TAG, e, "Failed to read key file")
                withContext(Dispatchers.Main) {
                    showError(
                        getString(R.string.identity_read_key_file_failed_fmt, mapped.message),
                        getString(R.string.identity_import_error_title),
                        copyText = mapped.technicalDetail
                    )
                }
            }
        }
    }

    /**
     * Show a two-field import dialog: display name (shown in the key list) and
     * SSH alias (used for IdentityFile resolution, defaults to SSH convention).
     * Both fields are pre-filled and user-editable.
     */
    private fun promptForKeyNameAndAlias(
        defaultName: String,
        defaultAlias: String,
        onConfirm: (name: String, alias: String) -> Unit
    ) {
        val ctx = requireContext()
        val form = DialogFields.form(ctx)

        val nameEdit = DialogFields.addText(
            form,
            hint = getString(R.string.identity_import_name_hint),
            initial = defaultName,
            helper = getString(R.string.identity_import_name_helper)
        )
        nameEdit.setSelection(nameEdit.text?.length ?: 0)

        val aliasEdit = DialogFields.addText(
            form,
            hint = getString(R.string.identity_import_alias_hint),
            initial = defaultAlias,
            helper = getString(R.string.identity_import_alias_helper),
            monospace = true
        )
        aliasEdit.setSelection(aliasEdit.text?.length ?: 0)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.identity_import_key_title))
            .setView(form.root)
            .setPositiveButton(getString(R.string.menu_import)) { _, _ ->
                val name = nameEdit.text.toString().trim().ifBlank { defaultName }
                val alias = aliasEdit.text.toString().trim().ifBlank { defaultAlias }
                onConfirm(name, alias)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun importKeyContent(keyContent: String, filename: String, keyAlias: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = app.keyStorage.importKeyFromText(
                    keyContent = keyContent,
                    passphrase = null,
                    keyName = extractKeyNameFromFilename(filename),
                    keyAlias = keyAlias
                )
                withContext(Dispatchers.Main) {
                    when (result) {
                        is ImportResult.Success -> {
                            Logger.i(TAG, "Key imported: ${result.keyId}")
                            Toast.makeText(requireContext(), getString(R.string.identity_ssh_key_imported_toast), Toast.LENGTH_SHORT).show()
                        }
                        is ImportResult.Error -> {
                            if (result.errorType == KeyImportErrorType.ENCRYPTED_NEEDS_PASSPHRASE ||
                                result.errorType == KeyImportErrorType.WRONG_PASSPHRASE
                            ) {
                                showPassphraseDialog(keyContent, filename, keyAlias)
                            } else {
                                showError(
                                    getString(R.string.identity_key_import_failed_fmt, result.errorType.toUserMessage(requireContext())),
                                    getString(R.string.identity_import_failed_title),
                                    copyText = result.technicalDetail
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(requireContext(), TAG, e, "Key import failed")
                withContext(Dispatchers.Main) {
                    showError(
                        getString(R.string.identity_key_import_failed_exception_fmt, mapped.message),
                        getString(R.string.identity_import_error_title),
                        copyText = mapped.technicalDetail
                    )
                }
            }
        }
    }

    private fun showPassphraseDialog(keyContent: String, filename: String, keyAlias: String? = null) {
        val form = DialogFields.form(requireContext())
        val edit = DialogFields.addSecret(form, getString(R.string.identity_import_passphrase_hint))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.identity_encrypted_key_title))
            .setMessage(getString(R.string.identity_encrypted_key_message))
            .setView(form.root)
            .setPositiveButton(getString(R.string.menu_import)) { _, _ ->
                val passphrase = edit.text.toString()
                importKeyWithPassphrase(keyContent, filename, passphrase, keyAlias)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun importKeyWithPassphrase(
        keyContent: String,
        filename: String,
        passphrase: String,
        keyAlias: String? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = app.keyStorage.importKeyFromText(
                    keyContent = keyContent,
                    passphrase = passphrase,
                    keyName = extractKeyNameFromFilename(filename),
                    keyAlias = keyAlias
                )
                withContext(Dispatchers.Main) {
                    when (result) {
                        is ImportResult.Success -> {
                            Logger.i(TAG, "Encrypted key imported: ${result.keyId}")
                            Toast.makeText(requireContext(), getString(R.string.identity_ssh_key_imported_toast), Toast.LENGTH_SHORT).show()
                        }
                        is ImportResult.Error -> {
                            Logger.e(TAG, "Encrypted key import failed: ${result.errorType} (${result.technicalDetail})")
                            showError(
                                getString(R.string.identity_import_failed_fmt, result.errorType.toUserMessage(requireContext())),
                                getString(R.string.identity_import_failed_title),
                                copyText = result.technicalDetail
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(requireContext(), TAG, e, "Encrypted key import failed")
                withContext(Dispatchers.Main) {
                    showError(
                        getString(R.string.identity_encrypted_key_import_failed_fmt, mapped.message),
                        getString(R.string.identity_import_error_title),
                        copyText = mapped.technicalDetail
                    )
                }
            }
        }
    }

    private fun generateKey(keyType: KeyType, keySize: Int, keyName: String) {
        Toast.makeText(requireContext(), getString(R.string.identity_generating_key_toast), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = app.keyStorage.generateKeyPair(keyType, keySize, keyName)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is GenerateResult.Success ->
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(getString(R.string.identity_key_generated_title))
                                .setMessage(getString(R.string.identity_key_generated_message_fmt, keyName, result.fingerprint))
                                .setPositiveButton(getString(R.string.ok), null)
                                .show()
                        is GenerateResult.Error ->
                            showError(getString(R.string.identity_generate_key_failed_fmt, result.message), getString(R.string.identity_generation_failed_title))
                    }
                }
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(requireContext(), TAG, e, "Key generation failed")
                withContext(Dispatchers.Main) {
                    showError(
                        getString(R.string.identity_key_generation_failed_exception_fmt, mapped.message),
                        getString(R.string.status_error),
                        copyText = mapped.technicalDetail
                    )
                }
            }
        }
    }

    private fun confirmDeleteSshKey(key: StoredKey) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.identity_delete_ssh_key_title))
            .setMessage(getString(R.string.identity_delete_ssh_key_message_fmt, key.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteSshKey(key) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteSshKey(key: StoredKey) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.withTransaction {
                app.database.connectionDao().clearKeyFromConnections(key.keyId)
                app.database.connectionDao().clearProxyKeyFromConnections(key.keyId)
                app.database.identityDao().clearKeyFromIdentities(key.keyId)
                app.database.keyDao().deleteKey(key)
                // H6 — record the deletion so it propagates and is not resurrected.
                TombstoneRecorder.record(app, TombstoneRecorder.KEY, key.keyId)
            }
            try { app.securePasswordManager.clearPassword("key_passphrase_${key.keyId}") } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), getString(R.string.identity_ssh_key_deleted_toast), Toast.LENGTH_SHORT).show()
                Logger.d(TAG, "Deleted SSH key: ${key.name}")
            }
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /**
     * Resolve a SAF content:// URI to its DISPLAY_NAME, or null.
     * Raw `lastPathSegment` for SAF URIs returns the internal document ID
     * (e.g. "msf:1000003152"), which is not useful as a key name.
     */
    private fun resolveDisplayName(uri: Uri): String? = try {
        requireContext().contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        Logger.w(TAG, "Display name lookup failed: ${e.message}")
        null
    }

    /** Turn a filename like "id_ed25519.pem" into a human label "id ed25519". */
    private fun extractKeyNameFromFilename(filename: String): String =
        filename.replace(Regex("\\.(pem|key|pub)$"), "").replace("_", " ").trim()

    companion object {
        private const val TAG = "AuthKeysFragment"
        fun newInstance() = AuthKeysFragment()
    }
}
