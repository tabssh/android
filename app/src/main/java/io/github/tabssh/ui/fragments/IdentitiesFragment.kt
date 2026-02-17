package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.ui.activities.KeyManagementActivity
import io.github.tabssh.ui.adapters.IdentityAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment for managing users (identities) and SSH keys
 * 
 * Identities are reusable credential sets (username + auth method) that can be
 * shared across multiple connections, similar to JuiceSSH's "Identities" feature.
 */
class IdentitiesFragment : Fragment() {
    
    private lateinit var app: TabSSHApplication
    private lateinit var identityAdapter: IdentityAdapter
    
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
        
        setupIdentitiesSection(view)
        loadData()
    }
    
    private fun setupIdentitiesSection(view: View) {
        // Setup identities RecyclerView
        identityAdapter = IdentityAdapter(
            onEdit = { identity -> showEditDialog(identity) },
            onDelete = { identity -> showDeleteConfirmation(identity) }
        )
        
        val recyclerView = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_users)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = identityAdapter
        
        // Show/hide empty state
        identityAdapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                updateEmptyState(view)
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                updateEmptyState(view)
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                updateEmptyState(view)
            }
        })
        
        // FAB shows menu with options
        view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_add_identity).setOnClickListener {
            showAddOptionsMenu()
        }

        // Empty state button
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_add_identity).setOnClickListener {
            showCreateDialog()
        }
    }
    
    private fun updateEmptyState(view: View) {
        val isEmpty = identityAdapter.itemCount == 0
        view.findViewById<View>(R.id.layout_empty_state).visibility = if (isEmpty) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.recycler_users).visibility = if (isEmpty) View.GONE else View.VISIBLE
    }
    
    private fun showAddOptionsMenu() {
        val options = arrayOf(
            "➕ Add Identity",
            "📥 Import SSH Key",
            "🔐 Generate SSH Key"
        )
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Create New")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateDialog()
                    1 -> navigateToKeyManagement(importMode = true)
                    2 -> navigateToKeyManagement(generateMode = true)
                }
            }
            .show()
    }
    
    private fun navigateToKeyManagement(importMode: Boolean = false, generateMode: Boolean = false) {
        val intent = Intent(requireContext(), KeyManagementActivity::class.java)
        if (importMode) {
            intent.putExtra("action", "import")
        } else if (generateMode) {
            intent.putExtra("action", "generate")
        }
        startActivity(intent)
    }
    
    private fun loadData() {
        // Load identities
        lifecycleScope.launch {
            app.database.identityDao().getAllIdentities().collect { identities ->
                identityAdapter.submitList(identities)
                Logger.d("IdentitiesFragment", "Loaded ${identities.size} identities")
            }
        }
        
        // Load SSH keys count (for display)
        lifecycleScope.launch {
            val keysCount = app.database.keyDao().getKeyCount()
            Logger.d("IdentitiesFragment", "Found $keysCount SSH keys")
        }
    }
    
    private fun showCreateDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_identity, null)
        
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_name)
        val usernameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.edit_description)
        val authTypeSpinner = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spinner_auth_type)
        val passwordLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_password)
        val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.edit_password)
        val sshKeyLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_ssh_key)
        val sshKeySpinner = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spinner_ssh_key)
        
        // Setup auth type spinner
        val authTypes = listOf("Password", "SSH Key", "Keyboard Interactive")
        val authAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authTypes)
        authTypeSpinner.setAdapter(authAdapter)
        authTypeSpinner.setText(authTypes[0], false)
        
        // Load SSH keys for selector
        lifecycleScope.launch(Dispatchers.IO) {
            val keys = app.database.keyDao().getKeyCount()
            val allKeys = if (keys > 0) {
                // Get all keys from database
                app.database.keyDao().getRecentlyUsedKeys(100)
            } else {
                emptyList()
            }
            
            val keyNames = listOf("No Key") + allKeys.map { "${it.name} (${it.keyType})" }
            val keyAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, keyNames)
            withContext(Dispatchers.Main) {
                sshKeySpinner.setAdapter(keyAdapter)
                if (keyNames.isNotEmpty()) {
                    sshKeySpinner.setText(keyNames[0], false)
                }
            }
        }
        
        // Show/hide fields based on auth type
        authTypeSpinner.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> { // Password
                    passwordLayout.visibility = View.VISIBLE
                    sshKeyLayout.visibility = View.GONE
                }
                1 -> { // SSH Key
                    passwordLayout.visibility = View.GONE
                    sshKeyLayout.visibility = View.VISIBLE
                }
                2 -> { // Keyboard Interactive
                    passwordLayout.visibility = View.GONE
                    sshKeyLayout.visibility = View.GONE
                }
            }
        }
        
        // Default: show password field
        passwordLayout.visibility = View.VISIBLE
        sshKeyLayout.visibility = View.GONE
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Create Identity")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString()
                val username = usernameInput.text.toString()
                val description = descriptionInput.text.toString()
                val password = passwordInput.text.toString()
                val authTypePosition = authTypes.indexOf(authTypeSpinner.text.toString())
                val authType = when (authTypePosition) {
                    0 -> AuthType.PASSWORD
                    1 -> AuthType.PUBLIC_KEY
                    2 -> AuthType.KEYBOARD_INTERACTIVE
                    else -> AuthType.PASSWORD
                }
                
                if (name.isNotBlank() && username.isNotBlank()) {
                    // Get selected SSH key ID if SSH Key auth type
                    val selectedKeyId: String? = if (authType == AuthType.PUBLIC_KEY) {
                        val selectedKeyIndex = sshKeySpinner.text.toString().let { text ->
                            if (text == "No Key") -1 else {
                                // Extract key from list by matching text
                                -1 // Will be updated below with actual key ID lookup
                            }
                        }
                        null // TODO: Need async key lookup - for now null
                    } else null

                    createIdentity(
                        name = name,
                        username = username,
                        authType = authType,
                        password = if (authType == AuthType.PASSWORD) password.ifBlank { null } else null,
                        keyId = selectedKeyId,
                        description = description.ifBlank { null }
                    )
                } else {
                    android.widget.Toast.makeText(requireContext(), "Name and username are required", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showEditDialog(identity: Identity) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_identity, null)
        
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_name)
        val usernameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.edit_description)
        val authTypeSpinner = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spinner_auth_type)
        val passwordLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_password)
        val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.edit_password)
        val sshKeyLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_ssh_key)
        val sshKeySpinner = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spinner_ssh_key)
        
        // Pre-fill values
        nameInput.setText(identity.name)
        usernameInput.setText(identity.username)
        descriptionInput.setText(identity.description ?: "")
        
        // Setup auth type spinner
        val authTypes = listOf("Password", "SSH Key", "Keyboard Interactive")
        val authAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authTypes)
        authTypeSpinner.setAdapter(authAdapter)
        val authTypeIndex = when (identity.authType) {
            AuthType.PASSWORD -> 0
            AuthType.PUBLIC_KEY -> 1
            AuthType.KEYBOARD_INTERACTIVE -> 2
            AuthType.GSSAPI -> 1
        }
        authTypeSpinner.setText(authTypes[authTypeIndex], false)
        
        // Load SSH keys
        lifecycleScope.launch(Dispatchers.IO) {
            val keysCount = app.database.keyDao().getKeyCount()
            val allKeys = if (keysCount > 0) {
                app.database.keyDao().getRecentlyUsedKeys(100)
            } else {
                emptyList()
            }
            
            val keyNames = listOf("No Key") + allKeys.map { "${it.name} (${it.keyType})" }
            val keyAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, keyNames)
            withContext(Dispatchers.Main) {
                sshKeySpinner.setAdapter(keyAdapter)
                if (keyNames.isNotEmpty()) {
                    sshKeySpinner.setText(keyNames[0], false)
                }
            }
        }
        
        // Show/hide fields based on auth type
        authTypeSpinner.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> { // Password
                    passwordLayout.visibility = View.VISIBLE
                    sshKeyLayout.visibility = View.GONE
                }
                1 -> { // SSH Key
                    passwordLayout.visibility = View.GONE
                    sshKeyLayout.visibility = View.VISIBLE
                }
                2 -> { // Keyboard Interactive
                    passwordLayout.visibility = View.GONE
                    sshKeyLayout.visibility = View.GONE
                }
            }
        }
        
        // Set initial visibility based on current auth type
        when (authTypeIndex) {
            0 -> {
                passwordLayout.visibility = View.VISIBLE
                sshKeyLayout.visibility = View.GONE
            }
            1 -> {
                passwordLayout.visibility = View.GONE
                sshKeyLayout.visibility = View.VISIBLE
            }
            2 -> {
                passwordLayout.visibility = View.GONE
                sshKeyLayout.visibility = View.GONE
            }
        }
        
        // Pre-fill password if exists
        passwordInput.setText(identity.password ?: "")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit Identity")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString()
                val username = usernameInput.text.toString()
                val description = descriptionInput.text.toString()
                val password = passwordInput.text.toString()
                val authTypePosition = authTypes.indexOf(authTypeSpinner.text.toString())
                val authType = when (authTypePosition) {
                    0 -> AuthType.PASSWORD
                    1 -> AuthType.PUBLIC_KEY
                    2 -> AuthType.KEYBOARD_INTERACTIVE
                    else -> AuthType.PASSWORD
                }

                if (name.isNotBlank() && username.isNotBlank()) {
                    updateIdentity(identity.copy(
                        name = name,
                        username = username,
                        authType = authType,
                        password = if (authType == AuthType.PASSWORD) password.ifBlank { null } else null,
                        description = description.ifBlank { null },
                        modifiedAt = System.currentTimeMillis()
                    ))
                } else {
                    android.widget.Toast.makeText(requireContext(), "Name and username are required", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showDeleteConfirmation(identity: Identity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Identity")
            .setMessage("Delete identity \"${identity.name}\"? Connections using this identity will need to be reconfigured.")
            .setPositiveButton("Delete") { _, _ ->
                deleteIdentity(identity)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun createIdentity(name: String, username: String, authType: AuthType, password: String?, keyId: String?, description: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val identity = Identity(
                name = name,
                username = username,
                authType = authType,
                password = password,
                keyId = keyId,
                description = description
            )
            app.database.identityDao().insert(identity)
            
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(requireContext(), "Identity \"$name\" created", android.widget.Toast.LENGTH_SHORT).show()
                Logger.d("IdentitiesFragment", "Created identity: $name")
            }
        }
    }
    
    private fun updateIdentity(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.identityDao().update(identity)
            
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(requireContext(), "Identity updated", android.widget.Toast.LENGTH_SHORT).show()
                Logger.d("IdentitiesFragment", "Updated identity: ${identity.name}")
            }
        }
    }
    
    private fun deleteIdentity(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.identityDao().delete(identity)
            
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(requireContext(), "Identity deleted", android.widget.Toast.LENGTH_SHORT).show()
                Logger.d("IdentitiesFragment", "Deleted identity: ${identity.name}")
            }
        }
    }
    
    companion object {
        fun newInstance() = IdentitiesFragment()
    }
}
