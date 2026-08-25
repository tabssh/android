package io.github.tabssh.ui.fragments

import io.github.tabssh.sync.tombstone.TombstoneRecorder
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
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.ui.adapters.IdentityAdapter
import io.github.tabssh.ui.fragments.AuthConstants.PASSWORD_MASK
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/** Auth tab — SSH sub-tab: host identities (SSH auth credential sets). */
class AuthSshFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var identityAdapter: IdentityAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_auth_ssh, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = tabSSHApp

        setupHostIdentitiesSection(view)
        observeData()
    }

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
        view.findViewById<MaterialButton>(R.id.button_identities_empty_cta).setOnClickListener {
            showCreateIdentityDialog()
        }
    }

    private fun updateIdentitiesEmptyState(view: View) {
        val empty = identityAdapter.itemCount == 0
        view.findViewById<View>(R.id.recycler_identities).visibility = if (empty) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.text_identities_empty).visibility = if (empty) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.button_identities_empty_cta).visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.database.identityDao().getAllIdentities().collect { list ->
                        identityAdapter.submitList(list)
                        Logger.d(TAG, "Loaded ${list.size} host identities")
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
                getString(R.string.identity_menu_edit),
                getString(R.string.identity_menu_apply_to_connections),
                getString(R.string.identity_menu_view_linked_connections),
                getString(R.string.identity_menu_delete)
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

        val authTypes = listOf(
            getString(R.string.password_hint),
            getString(R.string.identity_auth_type_ssh_key),
            getString(R.string.identity_auth_type_keyboard_interactive)
        )
        authTypeSpinner.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authTypes)
        )

        val initAuthIndex = when (existing?.authType) {
            AuthType.PASSWORD -> 0
            AuthType.PUBLIC_KEY -> 1
            AuthType.KEYBOARD_INTERACTIVE -> 2
            null -> 0
        }
        authTypeSpinner.setText(authTypes[initAuthIndex], false)

        var allKeysList = listOf<StoredKey>()
        lifecycleScope.launch(Dispatchers.IO) {
            allKeysList = app.database.keyDao().getAllKeysList()
            val keyNames = listOf(getString(R.string.identity_no_key)) + allKeysList.map { "${it.name} (${it.keyType})" }
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
            passwordInput.hint = getString(R.string.identity_password_set_hint)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) getString(R.string.identity_create_title) else getString(R.string.identity_edit_title))
            .setView(dialogView)
            .setPositiveButton(if (existing == null) getString(R.string.container_create) else getString(R.string.save)) { _, _ ->
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
                    if (sel == getString(R.string.identity_no_key)) null
                    else allKeysList.find { "${it.name} (${it.keyType})" == sel }?.keyId
                } else null

                if (name.isBlank() || username.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.identity_name_username_required), Toast.LENGTH_SHORT).show()
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
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showApplyToConnectionsDialog(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val allConnections = app.database.connectionDao().getAllConnectionsList()
            if (allConnections.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.identity_no_connections_available), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val linked = app.database.connectionDao()
                .getConnectionsByIdentity(identity.id).map { it.id }.toSet()
            val names = allConnections.map { "${it.name} (${it.username}@${it.host})" }.toTypedArray()
            val checked = allConnections.map { it.id in linked }.toBooleanArray()

            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.identity_apply_to_title_fmt, identity.name))
                    .setMultiChoiceItems(names, checked) { _, i, v -> checked[i] = v }
                    .setPositiveButton(getString(R.string.terminal_apply)) { _, _ ->
                        val ids = allConnections.filterIndexed { i, _ -> checked[i] }.map { it.id }
                        applyIdentityToConnections(identity, ids)
                    }
                    .setNeutralButton(getString(R.string.select_all)) { dialog, _ ->
                        checked.fill(true)
                        (dialog as? androidx.appcompat.app.AlertDialog)?.listView?.let { lv ->
                            for (i in 0 until lv.count) lv.setItemChecked(i, true)
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }

    private fun applyIdentityToConnections(identity: Identity, connectionIds: List<String>) {
        if (connectionIds.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.identity_no_connections_selected), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                app.database.connectionDao().applyIdentityToConnections(identity.id, connectionIds)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.identity_applied_to_connections_fmt, identity.name, connectionIds.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.identity_apply_failed_fmt, e.message), Toast.LENGTH_SHORT).show()
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
                        .setTitle(getString(R.string.identity_linked_connections_title))
                        .setMessage(getString(R.string.identity_no_linked_connections_message))
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                } else {
                    val list = linked.joinToString("\n") { getString(R.string.identity_linked_connection_line_fmt, it.name, it.host) }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.identity_connections_using_title_fmt, identity.name))
                        .setMessage(getString(R.string.identity_connections_count_message_fmt, linked.size, list))
                        .setPositiveButton(getString(R.string.ok), null)
                        .setNeutralButton(getString(R.string.identity_remove_all_button)) { _, _ -> removeIdentityFromAllConnections(identity) }
                        .show()
                }
            }
        }
    }

    private fun removeIdentityFromAllConnections(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.connectionDao().removeIdentityFromAllConnections(identity.id)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), getString(R.string.identity_removed_from_all_connections), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteIdentity(identity: Identity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.identity_delete_title))
            .setMessage(getString(R.string.identity_delete_message_fmt, identity.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteIdentity(identity) }
            .setNegativeButton(getString(R.string.cancel), null)
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
                Toast.makeText(requireContext(), getString(R.string.identity_created_toast_fmt, name), Toast.LENGTH_SHORT).show()
                Logger.d(TAG, "Created identity: $name")
                // Immediately offer to link the new identity to connections so the
                // user doesn't have to discover "Apply to Connections" separately.
                showApplyToConnectionsDialog(identity)
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
                Toast.makeText(requireContext(), getString(R.string.identity_updated_toast), Toast.LENGTH_SHORT).show()
                Logger.d(TAG, "Updated identity: ${identity.name}")
            }
        }
    }

    private fun deleteIdentity(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.withTransaction {
                app.database.connectionDao().removeIdentityFromAllConnections(identity.id)
                app.database.identityDao().delete(identity)
                // H6 — record the deletion so it propagates and is not resurrected.
                TombstoneRecorder.record(app, TombstoneRecorder.IDENTITY, identity.id)
            }
            try { app.securePasswordManager.clearPassword("identity_${identity.id}") } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), getString(R.string.identity_deleted_toast), Toast.LENGTH_SHORT).show()
                Logger.d(TAG, "Deleted identity: ${identity.name}")
            }
        }
    }

    companion object {
        private const val TAG = "AuthSshFragment"
        fun newInstance() = AuthSshFragment()
    }
}
