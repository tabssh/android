package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityIdentityManagementBinding
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.ui.adapters.IdentityAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Identity Management Activity
 * Manage reusable credential identities
 */
class IdentityManagementActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityIdentityManagementBinding
    private lateinit var app: TabSSHApplication
    private lateinit var adapter: IdentityAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        app = application as TabSSHApplication
        binding = ActivityIdentityManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupRecyclerView()
        setupFab()
        loadIdentities()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Manage Identities"
        }
    }
    
    private fun setupRecyclerView() {
        adapter = IdentityAdapter(
            onEdit = { identity -> showEditDialog(identity) },
            onDelete = { identity -> showDeleteConfirmation(identity) }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }
    
    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            showCreateDialog()
        }
    }
    
    private fun loadIdentities() {
        lifecycleScope.launch {
            app.database.identityDao().getAllIdentities().collect { identities ->
                adapter.submitList(identities)
                
                // Show/hide empty state
                if (identities.isEmpty()) {
                    binding.emptyStateLayout.visibility = android.view.View.VISIBLE
                    binding.recyclerView.visibility = android.view.View.GONE
                } else {
                    binding.emptyStateLayout.visibility = android.view.View.GONE
                    binding.recyclerView.visibility = android.view.View.VISIBLE
                }
            }
        }
    }
    
    private fun showCreateDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_identity, null)
        
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_name)
        val usernameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.edit_description)
        val authTypeSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_auth_type)
        
        // Setup auth type spinner
        val authTypes = listOf("Password", "SSH Key", "Keyboard Interactive")
        authTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, authTypes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Create Identity")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString()
                val username = usernameInput.text.toString()
                val description = descriptionInput.text.toString()
                val authType = when (authTypeSpinner.selectedItemPosition) {
                    0 -> AuthType.PASSWORD
                    1 -> AuthType.PUBLIC_KEY
                    2 -> AuthType.KEYBOARD_INTERACTIVE
                    else -> AuthType.PASSWORD
                }
                
                if (name.isNotBlank() && username.isNotBlank()) {
                    createIdentity(name, username, authType, description.ifBlank { null })
                } else {
                    android.widget.Toast.makeText(this, "Name and username are required", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showEditDialog(identity: Identity) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_identity, null)
        
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_name)
        val usernameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.edit_description)
        val authTypeSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_auth_type)
        
        // Pre-fill values
        nameInput.setText(identity.name)
        usernameInput.setText(identity.username)
        descriptionInput.setText(identity.description ?: "")
        
        // Setup auth type spinner
        val authTypes = listOf("Password", "SSH Key", "Keyboard Interactive")
        authTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, authTypes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        authTypeSpinner.setSelection(when (identity.authType) {
            AuthType.PASSWORD -> 0
            AuthType.PUBLIC_KEY -> 1
            AuthType.KEYBOARD_INTERACTIVE -> 2
            AuthType.GSSAPI -> 1 // Map to SSH Key
        })
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Identity")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString()
                val username = usernameInput.text.toString()
                val description = descriptionInput.text.toString()
                val authType = when (authTypeSpinner.selectedItemPosition) {
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
                        description = description.ifBlank { null },
                        modifiedAt = System.currentTimeMillis()
                    ))
                } else {
                    android.widget.Toast.makeText(this, "Name and username are required", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showDeleteConfirmation(identity: Identity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Identity")
            .setMessage("Delete identity \"${identity.name}\"? Connections using this identity will need to be reconfigured.")
            .setPositiveButton("Delete") { _, _ ->
                deleteIdentity(identity)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun createIdentity(name: String, username: String, authType: AuthType, description: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val identity = Identity(
                name = name,
                username = username,
                authType = authType,
                description = description
            )
            app.database.identityDao().insert(identity)
            
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@IdentityManagementActivity, "Identity created", android.widget.Toast.LENGTH_SHORT).show()
                Logger.d("IdentityManagementActivity", "Created identity: $name")
            }
        }
    }
    
    private fun updateIdentity(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.identityDao().update(identity)
            
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@IdentityManagementActivity, "Identity updated", android.widget.Toast.LENGTH_SHORT).show()
                Logger.d("IdentityManagementActivity", "Updated identity: ${identity.name}")
            }
        }
    }
    
    private fun deleteIdentity(identity: Identity) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.identityDao().delete(identity)
            
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@IdentityManagementActivity, "Identity deleted", android.widget.Toast.LENGTH_SHORT).show()
                Logger.d("IdentityManagementActivity", "Deleted identity: ${identity.name}")
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
