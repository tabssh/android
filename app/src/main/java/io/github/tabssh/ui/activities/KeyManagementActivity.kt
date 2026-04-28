package io.github.tabssh.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.keys.GenerateResult
import io.github.tabssh.crypto.keys.ImportResult
import io.github.tabssh.crypto.keys.KeyType
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.showError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity for managing SSH keys
 * Allows viewing, importing, generating and deleting stored SSH keys
 */
class KeyManagementActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateLayout: View
    private lateinit var fab: FloatingActionButton
    private lateinit var adapter: KeyAdapter
    private val keys = mutableListOf<StoredKey>()

    private val importKeyLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importKeyFromFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_management)

        app = application as TabSSHApplication

        // Setup toolbar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "SSH Key Management"
        }

        // Setup views
        recyclerView = findViewById(R.id.recycler_view_keys)
        emptyStateLayout = findViewById(R.id.layout_empty_state)
        fab = findViewById(R.id.fab_add_key)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = KeyAdapter(keys) { key -> showKeyDetails(key) }
        recyclerView.adapter = adapter

        // Setup FAB
        fab.setOnClickListener {
            showKeyManagementOptions()
        }

        // Setup empty state buttons
        findViewById<View>(R.id.button_import_key).setOnClickListener {
            importKeyFromFilePicker()
        }
        findViewById<View>(R.id.button_paste_key).setOnClickListener {
            showPasteKeyDialog()
        }
        findViewById<View>(R.id.button_generate_key).setOnClickListener {
            showGenerateKeyDialog()
        }

        // Load keys
        loadKeys()
        
        // Handle intent extras (from Identities screen)
        when (intent.getStringExtra("action")) {
            "import" -> importKeyFromFilePicker()
            "generate" -> showGenerateKeyDialog()
        }
    }

    private fun loadKeys() {
        lifecycleScope.launch {
            try {
                app.database.keyDao().getAllKeys().collect { loadedKeys ->
                    keys.clear()
                    keys.addAll(loadedKeys)
                    adapter.notifyDataSetChanged()

                    // Show empty state if no keys
                    if (keys.isEmpty()) {
                        emptyStateLayout.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyStateLayout.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Logger.e("KeyManagementActivity", "Failed to load keys", e)
            }
        }
    }

    private fun showKeyManagementOptions() {
        val options = arrayOf("Import from File", "Paste Key", "Generate New Key")

        AlertDialog.Builder(this)
            .setTitle("Add SSH Key")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importKeyFromFilePicker()
                    1 -> showPasteKeyDialog()
                    2 -> showGenerateKeyDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importKeyFromFilePicker() {
        importKeyLauncher.launch(arrayOf("*/*"))
    }

    private fun importKeyFromFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                    ?: return@launch

                val keyContent = inputStream.bufferedReader().readText()
                // Resolve the human-readable display name via SAF — the URI's
                // `lastPathSegment` for content:// returns the SAF document
                // ID (e.g. "msf:1000003152") which then gets stored as the
                // key name. Useless to the user. The OpenableColumns
                // DISPLAY_NAME query returns the real filename ("id_ed25519")
                // which `extractKeyNameFromFilename` then prettifies.
                val display = resolveDisplayName(uri) ?: uri.lastPathSegment ?: "Imported Key"
                val suggestion = extractKeyNameFromFilename(display)

                withContext(Dispatchers.Main) {
                    promptForKeyName(suggestion) { confirmedName ->
                        importKeyContent(keyContent, confirmedName)
                    }
                }
            } catch (e: Exception) {
                Logger.e("KeyManagementActivity", "Failed to read key file", e)
                withContext(Dispatchers.Main) {
                    showError("Failed to read key file: ${e.message}", "Import Error")
                }
            }
        }
    }

    /** Resolve a SAF content:// URI to its DISPLAY_NAME, or null. */
    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Logger.w("KeyManagementActivity", "Display name lookup failed: ${e.message}")
            null
        }
    }

    /**
     * Show a name-this-key dialog pre-filled with [suggestion]. The user can
     * accept, tweak, or replace; on positive button [onConfirm] gets the
     * final non-blank name (falls back to suggestion if user clears it).
     * Used so SAF-imported keys don't end up with names like "msf:1000003152".
     */
    private fun promptForKeyName(suggestion: String, onConfirm: (String) -> Unit) {
        val edit = android.widget.EditText(this).apply {
            setText(suggestion)
            setSelection(text.length)
            hint = "Key name"
        }
        AlertDialog.Builder(this)
            .setTitle("Name this key")
            .setMessage("This is the label TabSSH will show in the keys list.")
            .setView(edit)
            .setPositiveButton("Import") { _, _ ->
                val name = edit.text.toString().trim().ifBlank { suggestion }
                onConfirm(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasteKeyDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "Paste your private key here (PEM format)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 10
            maxLines = 20
        }

        AlertDialog.Builder(this)
            .setTitle("Paste SSH Private Key")
            .setView(editText)
            .setPositiveButton("Next") { _, _ ->
                val keyContent = editText.text.toString()
                if (keyContent.isNotBlank()) {
                    promptForKeyName("Pasted Key") { confirmedName ->
                        importKeyContent(keyContent, confirmedName)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importKeyContent(keyContent: String, filename: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = app.keyStorage.importKeyFromText(
                    keyContent = keyContent,
                    passphrase = null,
                    keyName = extractKeyNameFromFilename(filename)
                )

                withContext(Dispatchers.Main) {
                    when (result) {
                        is ImportResult.Success -> {
                            Logger.i("KeyManagementActivity", "Key imported successfully: ${result.keyId}")
                            Toast.makeText(this@KeyManagementActivity, "SSH key imported successfully!", Toast.LENGTH_SHORT).show()
                            loadKeys()
                        }
                        is ImportResult.Error -> {
                            Logger.e("KeyManagementActivity", "Key import failed: ${result.message}")
                            if (result.message.contains("encrypted") && result.message.contains("passphrase")) {
                                showPassphraseDialog(keyContent, filename)
                            } else {
                                showImportErrorDialog(result.message)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e("KeyManagementActivity", "Key import failed", e)
                withContext(Dispatchers.Main) {
                    showError("Key import failed: ${e.message}", "Import Error")
                }
            }
        }
    }

    private fun showPassphraseDialog(keyContent: String, filename: String) {
        val editText = android.widget.EditText(this).apply {
            hint = "Enter passphrase"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Encrypted Key")
            .setMessage("This key is encrypted. Please enter the passphrase.")
            .setView(editText)
            .setPositiveButton("Import") { _, _ ->
                val passphrase = editText.text.toString()
                importKeyWithPassphrase(keyContent, filename, passphrase)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importKeyWithPassphrase(keyContent: String, filename: String, passphrase: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = app.keyStorage.importKeyFromText(
                    keyContent = keyContent,
                    passphrase = passphrase,
                    keyName = extractKeyNameFromFilename(filename)
                )

                withContext(Dispatchers.Main) {
                    when (result) {
                        is ImportResult.Success -> {
                            Logger.i("KeyManagementActivity", "Encrypted key imported successfully")
                            Toast.makeText(this@KeyManagementActivity, "Encrypted SSH key imported successfully!", Toast.LENGTH_SHORT).show()
                            loadKeys()
                        }
                        is ImportResult.Error -> {
                            Logger.e("KeyManagementActivity", "Encrypted key import failed: ${result.message}")
                            showImportErrorDialog(result.message)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e("KeyManagementActivity", "Encrypted key import failed", e)
                withContext(Dispatchers.Main) {
                    showError("Encrypted key import failed: ${e.message}", "Import Error")
                }
            }
        }
    }

    private fun showImportErrorDialog(errorMessage: String) {
        io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
            context = this,
            title = "Key Import Failed",
            message = "Failed to import SSH key:\n\n$errorMessage"
        )
    }

    private fun extractKeyNameFromFilename(filename: String): String {
        return filename.replace(Regex("\\.(pem|key|pub)$"), "").replace("_", " ").trim()
    }

    private fun showGenerateKeyDialog() {
        val keyTypes = arrayOf("RSA 2048", "RSA 4096", "ECDSA P-256", "ECDSA P-384", "Ed25519")
        var selectedType = 0

        AlertDialog.Builder(this)
            .setTitle("Generate SSH Key")
            .setSingleChoiceItems(keyTypes, 0) { _, which ->
                selectedType = which
            }
            .setPositiveButton("Generate") { _, _ ->
                val nameEditText = android.widget.EditText(this).apply {
                    hint = "Key name (e.g., my-server-key)"
                }

                AlertDialog.Builder(this)
                    .setTitle("Key Name")
                    .setView(nameEditText)
                    .setPositiveButton("Generate") { _, _ ->
                        val keyName = nameEditText.text.toString().takeIf { it.isNotBlank() } ?: "generated-key"
                        val (keyType, keySize) = when (selectedType) {
                            0 -> Pair(KeyType.RSA, 2048)
                            1 -> Pair(KeyType.RSA, 4096)
                            2 -> Pair(KeyType.ECDSA, 256)
                            3 -> Pair(KeyType.ECDSA, 384)
                            4 -> Pair(KeyType.ED25519, 256)
                            else -> Pair(KeyType.RSA, 2048)
                        }
                        generateKey(keyType, keySize, keyName)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateKey(keyType: KeyType, keySize: Int, keyName: String) {
        // Show indeterminate progress
        Toast.makeText(this, "Generating SSH key...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = app.keyStorage.generateKeyPair(keyType, keySize, keyName)

                withContext(Dispatchers.Main) {

                    when (result) {
                        is GenerateResult.Success -> {
                            AlertDialog.Builder(this@KeyManagementActivity)
                                .setTitle("Key Generated Successfully")
                                .setMessage("SSH key '$keyName' has been generated.\n\nFingerprint:\n${result.fingerprint}")
                                .setPositiveButton("OK") { _, _ ->
                                    loadKeys()
                                }
                                .show()
                        }
                        is GenerateResult.Error -> {
                            AlertDialog.Builder(this@KeyManagementActivity)
                                .setTitle("Key Generation Failed")
                                .setMessage("Failed to generate SSH key:\n\n${result.message}")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e("KeyManagementActivity", "Key generation failed", e)
                withContext(Dispatchers.Main) {
                    showError("Key generation failed: ${e.message}", "Error")
                }
            }
        }
    }

    private fun showKeyDetails(key: StoredKey) {
        val certInfo = key.certificate?.let {
            val firstField = it.trim().substringBefore(' ')
            "Certificate: ✓ attached ($firstField)"
        } ?: "Certificate: — none"
        AlertDialog.Builder(this)
            .setTitle(key.name)
            .setMessage("""
                Type: ${key.keyType}
                Fingerprint: ${key.fingerprint}
                Created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(key.createdAt))}
                ${if (!key.comment.isNullOrEmpty()) "Comment: ${key.comment}\n" else ""}$certInfo
            """.trimIndent())
            .setPositiveButton("OK", null)
            .setNeutralButton("More…") { _, _ -> showMoreActionsDialog(key) }
            .setNegativeButton("Delete") { _, _ -> deleteKey(key) }
            .show()
    }

    private fun showMoreActionsDialog(key: StoredKey) {
        val items = mutableListOf("Rename", "Attach certificate (paste)…", "Attach certificate (file)…")
        if (key.certificate != null) items.add("Remove certificate")
        AlertDialog.Builder(this)
            .setTitle(key.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Rename" -> showRenameKeyDialog(key)
                    "Attach certificate (paste)…" -> showPasteCertDialog(key)
                    "Attach certificate (file)…" -> launchCertFilePicker(key)
                    "Remove certificate" -> setKeyCert(key, null, "Certificate removed")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Wave 2.2 — paste an OpenSSH user certificate (single-line *-cert.pub).
     */
    private fun showPasteCertDialog(key: StoredKey) {
        val edit = android.widget.EditText(this).apply {
            hint = "Paste *-cert.pub line (e.g. ssh-rsa-cert-v01@openssh.com AAAA…)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 8
        }
        AlertDialog.Builder(this)
            .setTitle("Attach OpenSSH Certificate")
            .setView(edit)
            .setPositiveButton("Attach") { _, _ ->
                val cert = edit.text.toString().trim()
                if (validateCert(cert)) setKeyCert(key, cert, "Certificate attached")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val attachCertLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pendingKey = pendingCertKey ?: return@registerForActivityResult
        pendingCertKey = null
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
                withContext(Dispatchers.Main) {
                    if (validateCert(text)) setKeyCert(pendingKey, text, "Certificate attached")
                }
            } catch (e: Exception) {
                Logger.e("KeyManagementActivity", "Failed to read cert file", e)
                withContext(Dispatchers.Main) {
                    showError("Failed to read certificate: ${e.message}", "Attach Error")
                }
            }
        }
    }
    private var pendingCertKey: StoredKey? = null

    private fun launchCertFilePicker(key: StoredKey) {
        pendingCertKey = key
        attachCertLauncher.launch(arrayOf("*/*"))
    }

    private fun validateCert(cert: String): Boolean {
        if (!cert.contains("-cert-v01@openssh.com")) {
            showError("Doesn't look like an OpenSSH certificate (missing '-cert-v01@openssh.com').", "Invalid Certificate")
            return false
        }
        return true
    }

    private fun setKeyCert(key: StoredKey, cert: String?, toastMsg: String) {
        lifecycleScope.launch {
            try {
                app.database.keyDao().updateKey(key.copy(certificate = cert))
                Toast.makeText(this@KeyManagementActivity, toastMsg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e("KeyManagementActivity", "Failed to update certificate", e)
                showError("Failed to update certificate: ${e.message}", "Error")
            }
        }
    }

    private fun showRenameKeyDialog(key: StoredKey) {
        val editText = android.widget.EditText(this).apply {
            setText(key.name)
            hint = "Enter new name"
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle("Rename SSH Key")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank() && newName != key.name) {
                    renameKey(key, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameKey(key: StoredKey, newName: String) {
        lifecycleScope.launch {
            try {
                val updatedKey = key.copy(name = newName)
                app.database.keyDao().updateKey(updatedKey)
                Toast.makeText(this@KeyManagementActivity, "Key renamed to '$newName'", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e("KeyManagementActivity", "Failed to rename key", e)
                showError("Failed to rename key: ${e.message}", "Rename Error")
            }
        }
    }

    private fun deleteKey(key: StoredKey) {
        AlertDialog.Builder(this)
            .setTitle("Delete SSH Key")
            .setMessage("Are you sure you want to delete key '${key.name}'?\n\nThis action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.database.keyDao().deleteKey(key)
                        Toast.makeText(this@KeyManagementActivity, "Key deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Logger.e("KeyManagementActivity", "Failed to delete key", e)
                        showError("Failed to delete key: ${e.message}", "Delete Error")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Adapter for SSH keys list
     */
    private class KeyAdapter(
        private val keys: List<StoredKey>,
        private val onKeyClick: (StoredKey) -> Unit
    ) : RecyclerView.Adapter<KeyAdapter.KeyViewHolder>() {

        class KeyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.text_key_name)
            val typeText: TextView = view.findViewById(R.id.text_key_type)
            val fingerprintText: TextView = view.findViewById(R.id.text_key_fingerprint)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeyViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ssh_key, parent, false)
            return KeyViewHolder(view)
        }

        override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
            val key = keys[position]
            holder.nameText.text = key.name
            holder.typeText.text = key.keyType.uppercase()
            holder.fingerprintText.text = key.fingerprint.take(32) + "..."

            holder.itemView.setOnClickListener {
                onKeyClick(key)
            }
        }

        override fun getItemCount() = keys.size
    }
}
