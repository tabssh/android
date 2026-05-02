package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.HypervisorPasswordStore
import io.github.tabssh.storage.database.entities.HypervisorAccount
import io.github.tabssh.ui.adapters.HypervisorAccountAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * CRUD screen for reusable hypervisor credential accounts.
 *
 * Mirrors `IdentityManagementActivity` but for the smaller
 * hypervisor-specific shape (just name + username + password + optional
 * realm). Password is persisted to the Keystore via
 * `HypervisorPasswordStore.storeAccountPassword`; the entity row holds
 * only metadata.
 */
class HypervisorAccountsActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var fab: FloatingActionButton
    private lateinit var emptyState: View
    private lateinit var adapter: HypervisorAccountAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hypervisor_accounts)

        app = application as TabSSHApplication

        toolbar = findViewById(R.id.toolbar)
        recycler = findViewById(R.id.recycler_accounts)
        fab = findViewById(R.id.fab_add)
        emptyState = findViewById(R.id.layout_empty_state)

        toolbar.setNavigationOnClickListener { finish() }

        adapter = HypervisorAccountAdapter(
            onEdit = { showEditDialog(it) },
            onDelete = { confirmDelete(it) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        fab.setOnClickListener { showEditDialog(null) }

        observeAccounts()
    }

    private fun observeAccounts() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.hypervisorAccountDao().getAllAccounts().collect { list ->
                    adapter.submit(list)
                    if (list.isEmpty()) {
                        recycler.visibility = View.GONE
                        emptyState.visibility = View.VISIBLE
                    } else {
                        recycler.visibility = View.VISIBLE
                        emptyState.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * Show the create/edit dialog. `existing == null` → create flow.
     * Password field is left BLANK on edit even when one is stored —
     * users have to re-enter to change. (Identity manager does the
     * same; avoids displaying a stored secret in cleartext.)
     */
    private fun showEditDialog(existing: HypervisorAccount?) {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_edit_hypervisor_account, null)

        val editName = view.findViewById<TextInputEditText>(R.id.edit_name)
        val editUsername = view.findViewById<TextInputEditText>(R.id.edit_username)
        val editPassword = view.findViewById<TextInputEditText>(R.id.edit_password)
        val editRealm = view.findViewById<TextInputEditText>(R.id.edit_realm)

        if (existing != null) {
            editName.setText(existing.name)
            editUsername.setText(existing.username)
            editRealm.setText(existing.realm ?: "")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "New hypervisor account" else "Edit account")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = editName.text?.toString()?.trim().orEmpty()
                val username = editUsername.text?.toString()?.trim().orEmpty()
                val password = editPassword.text?.toString().orEmpty()
                val realm = editRealm.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

                if (name.isBlank() || username.isBlank()) {
                    android.widget.Toast.makeText(
                        this, "Name and username are required",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val savedId = if (existing == null) {
                        // Create
                        app.database.hypervisorAccountDao().insert(
                            HypervisorAccount(name = name, username = username, realm = realm)
                        )
                    } else {
                        // Update
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

                    // Only persist password when one was supplied — empty
                    // edit-time password means "leave the stored Keystore
                    // value alone". For new rows, an empty password is
                    // stored explicitly so retrieve() returns "".
                    if (password.isNotEmpty() || existing == null) {
                        HypervisorPasswordStore.storeAccountPassword(
                            this@HypervisorAccountsActivity, savedId, password
                        )
                    }
                    Logger.i(
                        "HypervisorAccountsActivity",
                        if (existing == null) "Created account id=$savedId ($name)" else "Updated account id=$savedId ($name)"
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(account: HypervisorAccount) {
        lifecycleScope.launch {
            // Detect any hypervisor profiles still pointing at this account
            // — block delete with a clear message rather than orphaning the
            // FK silently. The user can re-link or unlink first.
            val linked = try {
                app.database.hypervisorDao().getAllList().count { it.accountId == account.id }
            } catch (_: Exception) { 0 }

            val message = if (linked > 0) {
                "$linked hypervisor${if (linked == 1) "" else "s"} still link to '${account.name}'. " +
                "Unlink them in their edit screen first, then delete the account."
            } else {
                "Delete account '${account.name}'?\n\n" +
                "The stored password will be cleared from the Keystore. " +
                "Hypervisors that pointed at this account will need a new credential."
            }

            MaterialAlertDialogBuilder(this@HypervisorAccountsActivity)
                .setTitle("Delete account")
                .setMessage(message)
                .setPositiveButton("Delete") { _, _ ->
                    if (linked > 0) return@setPositiveButton
                    lifecycleScope.launch {
                        app.database.hypervisorAccountDao().delete(account)
                        HypervisorPasswordStore.clearAccountPassword(
                            this@HypervisorAccountsActivity, account.id
                        )
                        Logger.i(
                            "HypervisorAccountsActivity",
                            "Deleted account id=${account.id} (${account.name})"
                        )
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
