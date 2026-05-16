package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.withTransaction
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.HypervisorPasswordStore
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.entities.HypervisorAccount
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.ui.activities.KeyManagementActivity
import io.github.tabssh.ui.adapters.HypervisorAccountAdapter
import io.github.tabssh.ui.adapters.IdentityAdapter
import io.github.tabssh.ui.adapters.StoredKeyAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified credentials screen — three sections on one scrollable page:
 *
 *   1. Host Identities      — SSH auth credential sets (username + auth method)
 *   2. Virtualization Identities — Hypervisor REST credentials (Proxmox/VMware/XCP-ng/OCI)
 *   3. SSH Keys             — Raw private keys used by host identities
 */
class IdentitiesFragment : Fragment() {

    private lateinit var app: TabSSHApplication

    private lateinit var identityAdapter: IdentityAdapter
    private lateinit var virtAdapter: HypervisorAccountAdapter
    private lateinit var keyAdapter: StoredKeyAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_identities, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = requireActivity().application as TabSSHApplication

        setupHostIdentitiesSection(view)
        setupVirtIdentitiesSection(view)
        setupSshKeysSection(view)
        observeData()
    }

    // ─── Host Identities ────────────────────────────────────────────────────

    private fun setupHostIdentitiesSection(view: View) {
        identityAdapter = IdentityAdapter(
            onEdit = { identity -> showIdentityOptionsMenu(identity) },
            onDelete = { identity -> confirmDeleteIdentity(identity) }
        )
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_identities)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = identityAdapter

        identityAdapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() { updateIdentitiesEmptyState(view) }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { updateIdentitiesEmptyState(view) }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { updateIdentitiesEmptyState(view) }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { updateIdentitiesEmptyState(view) }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { updateIdentitiesEmptyState(view) }
        })
        updateIdentitiesEmptyState(view)

        view.findViewById<MaterialButton>(R.id.btn_add_identity).setOnClickListener {
            showCreateIdentityDialog()
        }
    }

    private fun updateIdentitiesEmptyState(view: View) {
        val empty = identityAdapter.itemCount == 0
        view.findViewById<View>(R.id.recycler_identities).visibility = if (empty) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.text_identities_empty).visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ─── Virtualization Identities ──────────────────────────────────────────

    private fun setupVirtIdentitiesSection(view: View) {
        virtAdapter = HypervisorAccountAdapter(
            onEdit = { account -> showVirtAccountDialog(account) },
            onDelete = { account -> confirmDeleteVirtAccount(account) }
        )
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_virt_identities)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = virtAdapter

        view.findViewById<MaterialButton>(R.id.btn_add_virt_identity).setOnClickListener {
            showVirtAccountDialog(null)
        }
    }

    private fun updateVirtEmptyState(view: View, count: Int) {
        val empty = count == 0
        view.findViewById<View>(R.id.recycler_virt_identities).visibility = if (empty) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.text_virt_identities_empty).visibility = if (empty) View.VISIBLE else View.GONE
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
    }

    private fun updateKeysEmptyState(view: View) {
        val empty = keyAdapter.itemCount == 0
        view.findViewById<View>(R.id.recycler_ssh_keys).visibility = if (empty) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.text_ssh_keys_empty).visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ─── Data observation ────────────────────────────────────────────────────

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.database.identityDao().getAllIdentities().collect { list ->
                        identityAdapter.submitList(list)
                        Logger.d(TAG, "Loaded ${list.size} host identities")
                    }
                }
                launch {
                    app.database.hypervisorAccountDao().getAllAccounts().collect { list ->
                        virtAdapter.submit(list)
                        view?.let { updateVirtEmptyState(it, list.size) }
                        Logger.d(TAG, "Loaded ${list.size} virtualization identities")
                    }
                }
                launch {
                    app.database.keyDao().getAllKeys().collect { list ->
                        keyAdapter.submitList(list)
                        Logger.d(TAG, "Loaded ${list.size} SSH keys")
                    }
                }
            }
        }
    }

    // ─── Host Identity dialogs ───────────────────────────────────────────────

    private fun showIdentityOptionsMenu(identity: Identity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(identity.getDisplayName())
            .setItems(arrayOf(
                "✏️ Edit",
                "📋 Apply to Connections",
                "🔗 View Linked Connections",
                "🗑️ Delete"
            )) { _, which ->
                when (which) {
                    0 -> showEditIdentityDialog(identity)
                    1 -> showApplyToConnectionsDialog(identity)
                    2 -> showLinkedConnections(identity)
                    3 -> confirmDeleteIdentity(identity)
                }
            }
            .show()
    }

    private fun showCreateIdentityDialog() {
        showIdentityDialog(existing = null)
    }

    private fun showEditIdentityDialog(identity: Identity) {
        showIdentityDialog(existing = identity)
    }

    private fun showIdentityDialog(existing: Identity?) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_identity, null)

        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_name)
        val usernameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.edit_description)
        val authTypeSpinner = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spinner_auth_type)
        val passwordLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_password)
        val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.edit_password)
        val sshKeyLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_ssh_key)
        val sshKeySpinner = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spinner_ssh_key)

        existing?.let { id ->
            nameInput.setText(id.name)
            usernameInput.setText(id.username)
            descriptionInput.setText(id.description ?: "")
        }

        val authTypes = listOf("Password", "SSH Key", "Keyboard Interactive")
        authTypeSpinner.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authTypes)
        )

        val initAuthIndex = when (existing?.authType) {
            AuthType.PASSWORD -> 0
            AuthType.PUBLIC_KEY, AuthType.GSSAPI, AuthType.FIDO2_SECURITY_KEY -> 1
            AuthType.KEYBOARD_INTERACTIVE -> 2
            null -> 0
        }
        authTypeSpinner.setText(authTypes[initAuthIndex], false)

        var allKeysList = listOf<StoredKey>()
        lifecycleScope.launch(Dispatchers.IO) {
            allKeysList = app.database.keyDao().getAllKeysList()
            val keyNames = listOf("No Key") + allKeysList.map { "${it.name} (${it.keyType})" }
            val currentKeyIndex = existing?.keyId?.let { kid ->
                val idx = allKeysList.indexOfFirst { it.keyId == kid }
                if (idx >= 0) idx + 1 else 0
            } ?: 0

            withContext(Dispatchers.Main) {
                sshKeySpinner.setAdapter(
                    ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, keyNames)
                )
                sshKeySpinner.setText(keyNames[currentKeyIndex], false)
            }
        }

        fun applyAuthVisibility(position: Int) {
            passwordLayout.visibility = if (position == 0) View.VISIBLE else View.GONE
            sshKeyLayout.visibility = if (position == 1) View.VISIBLE else View.GONE
        }
        applyAuthVisibility(initAuthIndex)
        authTypeSpinner.setOnItemClickListener { _, _, pos, _ -> applyAuthVisibility(pos) }

        if (existing != null && !existing.password.isNullOrEmpty()) {
            passwordInput.setText(PASSWORD_MASK)
            passwordInput.hint = "Password set — leave to keep, or type to replace"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) "Create Identity" else "Edit Identity")
            .setView(dialogView)
            .setPositiveButton(if (existing == null) "Create" else "Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val username = usernameInput.text.toString().trim()
                val description = descriptionInput.text.toString().trim()
                val passwordText = passwordInput.text.toString()
                val authTypePos = authTypes.indexOf(authTypeSpinner.text.toString())
                val authType = when (authTypePos) {
                    1 -> AuthType.PUBLIC_KEY
                    2 -> AuthType.KEYBOARD_INTERACTIVE
                    else -> AuthType.PASSWORD
                }
                val selectedKeyId: String? = if (authType == AuthType.PUBLIC_KEY) {
                    val sel = sshKeySpinner.text.toString()
                    if (sel == "No Key") null
                    else allKeysList.find { "${it.name} (${it.keyType})" == sel }?.keyId
                } else null

                if (name.isBlank() || username.isBlank()) {
                    Toast.makeText(requireContext(), "Name and username are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (existing == null) {
                    val password = if (authType == AuthType.PASSWORD) passwordText.ifBlank { null } else null
                    createIdentity(name, username, authType, password, selectedKeyId, description.ifBlank { null })
                } else {
                    val newPassword = when {
                        authType != AuthType.PASSWORD -> null
                        passwordText == PASSWORD_MASK -> existing.password
                        passwordText.isBlank() -> null
                        else -> passwordText
                    }
                    updateIdentity(existing.copy(
                        name = name,
                        username = username,
                        authType = authType,
                        password = newPassword,
                        keyId = selectedKeyId,
                        description = description.ifBlank { null },
                        modifiedAt = System.currentTimeMillis()
                    ))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showApplyToConnectionsDialog(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val allConnections = app.database.connectionDao().getAllConnectionsList()
            if (allConnections.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "No connections available", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val linked = app.database.connectionDao()
                .getConnectionsByIdentity(identity.id).map { it.id }.toSet()
            val names = allConnections.map { "${it.name} (${it.username}@${it.host})" }.toTypedArray()
            val checked = allConnections.map { it.id in linked }.toBooleanArray()

            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Apply \"${identity.name}\" to:")
                    .setMultiChoiceItems(names, checked) { _, i, v -> checked[i] = v }
                    .setPositiveButton("Apply") { _, _ ->
                        val ids = allConnections.filterIndexed { i, _ -> checked[i] }.map { it.id }
                        applyIdentityToConnections(identity, ids)
                    }
                    .setNeutralButton("Select All") { dialog, _ ->
                        checked.fill(true)
                        (dialog as? androidx.appcompat.app.AlertDialog)?.listView?.let { lv ->
                            for (i in 0 until lv.count) lv.setItemChecked(i, true)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun applyIdentityToConnections(identity: Identity, connectionIds: List<String>) {
        if (connectionIds.isEmpty()) {
            Toast.makeText(requireContext(), "No connections selected", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                app.database.connectionDao().applyIdentityToConnections(identity.id, connectionIds)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Applied \"${identity.name}\" to ${connectionIds.size} connection(s)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Failed to apply identity: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                Logger.e(TAG, "Failed to apply identity", e)
            }
        }
    }

    private fun showLinkedConnections(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val linked = app.database.connectionDao().getConnectionsByIdentity(identity.id)
            withContext(Dispatchers.Main) {
                if (linked.isEmpty()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Linked Connections")
                        .setMessage("No connections are using this identity.\n\nTap \"Apply to Connections\" to link it.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    val list = linked.joinToString("\n") { "• ${it.name} (${it.host})" }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Connections using \"${identity.name}\"")
                        .setMessage("${linked.size} connection(s):\n\n$list")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Remove All") { _, _ -> removeIdentityFromAllConnections(identity) }
                        .show()
                }
            }
        }
    }

    private fun removeIdentityFromAllConnections(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.connectionDao().removeIdentityFromAllConnections(identity.id)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Removed identity from all connections", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteIdentity(identity: Identity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Identity")
            .setMessage("Delete \"${identity.name}\"? Connections using it will need to be reconfigured.")
            .setPositiveButton("Delete") { _, _ -> deleteIdentity(identity) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createIdentity(
        name: String, username: String, authType: AuthType,
        password: String?, keyId: String?, description: String?
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val identity = Identity(
                name = name,
                username = username,
                authType = authType,
                password = null,
                keyId = keyId,
                description = description
            )
            app.database.identityDao().insert(identity)
            if (password != null) {
                app.securePasswordManager.storePassword(
                    "identity_${identity.id}", password,
                    SecurePasswordManager.StorageLevel.ENCRYPTED
                )
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Identity \"$name\" created", Toast.LENGTH_SHORT).show()
                Logger.d(TAG, "Created identity: $name")
            }
        }
    }

    private fun updateIdentity(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val legacyPassword = identity.password
            val updated = identity.copy(password = null)
            app.database.identityDao().update(updated)
            if (legacyPassword != null) {
                app.securePasswordManager.storePassword(
                    "identity_${identity.id}", legacyPassword,
                    SecurePasswordManager.StorageLevel.ENCRYPTED
                )
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Identity updated", Toast.LENGTH_SHORT).show()
                Logger.d(TAG, "Updated identity: ${identity.name}")
            }
        }
    }

    private fun deleteIdentity(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.withTransaction {
                app.database.connectionDao().removeIdentityFromAllConnections(identity.id)
                app.database.identityDao().delete(identity)
            }
            try { app.securePasswordManager.clearPassword("identity_${identity.id}") } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Identity deleted", Toast.LENGTH_SHORT).show()
                Logger.d(TAG, "Deleted identity: ${identity.name}")
            }
        }
    }

    // ─── Virtualization Identity dialogs ────────────────────────────────────

    private fun showVirtAccountDialog(existing: HypervisorAccount?) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_hypervisor_account, null)

        val editName = dialogView.findViewById<TextInputEditText>(R.id.edit_name)
        val editUsername = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_password)
        val editRealm = dialogView.findViewById<TextInputEditText>(R.id.edit_realm)

        existing?.let {
            editName.setText(it.name)
            editUsername.setText(it.username)
            editRealm.setText(it.realm ?: "")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) "New Virtualization Identity" else "Edit Identity")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = editName.text?.toString()?.trim().orEmpty()
                val username = editUsername.text?.toString()?.trim().orEmpty()
                val password = editPassword.text?.toString().orEmpty()
                val realm = editRealm.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

                if (name.isBlank() || username.isBlank()) {
                    Toast.makeText(requireContext(), "Name and username are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val savedId: Long = if (existing == null) {
                        app.database.hypervisorAccountDao().insert(
                            HypervisorAccount(name = name, username = username, realm = realm)
                        )
                    } else {
                        app.database.hypervisorAccountDao().update(
                            existing.copy(
                                name = name,
                                username = username,
                                realm = realm,
                                modifiedAt = System.currentTimeMillis()
                            )
                        )
                        existing.id
                    }
                    if (password.isNotEmpty() || existing == null) {
                        HypervisorPasswordStore.storeAccountPassword(requireContext(), savedId, password)
                    }
                    Logger.i(TAG,
                        if (existing == null) "Created virt identity id=$savedId ($name)"
                        else "Updated virt identity id=$savedId ($name)"
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteVirtAccount(account: HypervisorAccount) {
        lifecycleScope.launch {
            val linked = try {
                app.database.hypervisorDao().getAllList().count { it.accountId == account.id }
            } catch (_: Exception) { 0 }

            val message = if (linked > 0) {
                "$linked hypervisor${if (linked == 1) "" else "s"} still link to \"${account.name}\". " +
                "Unlink them in their edit screen first."
            } else {
                "Delete \"${account.name}\"?\n\nThe stored password will be cleared from the Keystore."
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Virtualization Identity")
                .setMessage(message)
                .setPositiveButton("Delete") { _, _ ->
                    if (linked > 0) return@setPositiveButton
                    lifecycleScope.launch {
                        app.database.hypervisorAccountDao().delete(account)
                        HypervisorPasswordStore.clearAccountPassword(requireContext(), account.id)
                        Logger.i(TAG, "Deleted virt identity id=${account.id} (${account.name})")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ─── SSH Key dialogs ─────────────────────────────────────────────────────

    private fun showSshKeyAddMenu() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("SSH Key")
            .setItems(arrayOf("📥 Import SSH Key", "🔐 Generate SSH Key")) { _, which ->
                when (which) {
                    0 -> navigateToKeyManagement(importMode = true)
                    1 -> navigateToKeyManagement(generateMode = true)
                }
            }
            .show()
    }

    private fun showSshKeyOptionsMenu(key: StoredKey) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(key.getDisplayName())
            .setItems(arrayOf("✏️ Manage Key", "🗑️ Delete Key")) { _, which ->
                when (which) {
                    0 -> navigateToKeyManagement()
                    1 -> confirmDeleteSshKey(key)
                }
            }
            .show()
    }

    private fun navigateToKeyManagement(importMode: Boolean = false, generateMode: Boolean = false) {
        val intent = Intent(requireContext(), KeyManagementActivity::class.java)
        if (importMode) intent.putExtra("action", "import")
        else if (generateMode) intent.putExtra("action", "generate")
        startActivity(intent)
    }

    private fun confirmDeleteSshKey(key: StoredKey) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete SSH Key")
            .setMessage("Delete key \"${key.name}\"?\n\nIdentities using this key will lose their key association.")
            .setPositiveButton("Delete") { _, _ -> deleteSshKey(key) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSshKey(key: StoredKey) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.withTransaction {
                app.database.connectionDao().clearKeyFromConnections(key.keyId)
                app.database.connectionDao().clearProxyKeyFromConnections(key.keyId)
                app.database.identityDao().clearKeyFromIdentities(key.keyId)
                app.database.keyDao().deleteKey(key)
            }
            try { app.securePasswordManager.clearPassword("key_passphrase_${key.keyId}") } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "SSH key deleted", Toast.LENGTH_SHORT).show()
                Logger.d(TAG, "Deleted SSH key: ${key.name}")
            }
        }
    }

    companion object {
        private const val TAG = "IdentitiesFragment"
        private const val PASSWORD_MASK = "••••••••"
        fun newInstance() = IdentitiesFragment()
    }
}
