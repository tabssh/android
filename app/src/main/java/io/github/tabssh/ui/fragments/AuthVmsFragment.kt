package io.github.tabssh.ui.fragments

import io.github.tabssh.sync.tombstone.TombstoneRecorder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.HypervisorPasswordStore
import io.github.tabssh.storage.database.entities.HypervisorAccount
import io.github.tabssh.ui.adapters.HypervisorAccountAdapter
import io.github.tabssh.ui.fragments.AuthConstants.PASSWORD_MASK
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/** Auth tab — VMs sub-tab: hypervisor login credentials (Proxmox / VMware / XCP-ng). */
class AuthVmsFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var virtAdapter: HypervisorAccountAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_auth_vms, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = tabSSHApp

        setupVirtIdentitiesSection(view)
        observeData()
    }

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
        view.findViewById<MaterialButton>(R.id.button_virt_identities_empty_cta).setOnClickListener {
            showVirtAccountDialog(null)
        }
    }

    private fun updateVirtEmptyState(view: View, count: Int) {
        val empty = count == 0
        view.findViewById<View>(R.id.recycler_virt_identities).visibility = if (empty) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.text_virt_identities_empty).visibility = if (empty) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.button_virt_identities_empty_cta).visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.database.hypervisorAccountDao().getAllAccounts().collect { list ->
                        // OCI accounts are managed through the dedicated OCI wizard; exclude them here
                        val vmOnly = list.filter { it.authType != "oci_api_key" }
                        virtAdapter.submit(vmOnly)
                        view?.let { updateVirtEmptyState(it, vmOnly.size) }
                        Logger.d(TAG, "Loaded ${vmOnly.size} VM credentials (${list.size - vmOnly.size} OCI excluded)")
                    }
                }
            }
        }
    }

    // ─── Virtualization Identity dialogs ────────────────────────────────────

    private fun showVirtAccountDialog(existing: HypervisorAccount?) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_virt_identity, null)

        val editName     = dialogView.findViewById<TextInputEditText>(R.id.edit_name)
        val editUsername = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_password)
        val editRealm    = dialogView.findViewById<TextInputEditText>(R.id.edit_realm)

        existing?.let { acc ->
            editName.setText(acc.name)
            editUsername.setText(acc.username)
            editRealm.setText(acc.realm ?: "")
            lifecycleScope.launch(Dispatchers.IO) {
                val hasPw = HypervisorPasswordStore
                    .retrieveAccountPassword(requireContext(), acc.id)?.isNotBlank() == true
                withContext(Dispatchers.Main) {
                    if (hasPw) {
                        editPassword.setText(PASSWORD_MASK)
                        editPassword.hint = getString(R.string.identity_password_set_hint)
                    }
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) getString(R.string.identity_vm_cred_new_title) else getString(R.string.identity_vm_cred_edit_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = editName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.xcpng_name_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveVirtAccount(existing, name, editUsername, editPassword, editRealm)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Persist a [HypervisorAccount] credential. */
    private fun saveVirtAccount(
        existing: HypervisorAccount?,
        name: String,
        editUsername: TextInputEditText,
        editPassword: TextInputEditText,
        editRealm: TextInputEditText
    ) {
        val username = editUsername.text?.toString()?.trim().orEmpty()
        val password = editPassword.text?.toString().orEmpty()
        val realm = editRealm.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

        if (username.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.identity_username_required), Toast.LENGTH_SHORT).show()
            return
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
            // Only write to Keystore when a new password was typed (not the
            // display mask) or when creating a new account.
            val shouldSavePassword = when {
                // user left the mask unchanged
                password == PASSWORD_MASK -> false
                // new account
                existing == null          -> true
                // user typed a replacement
                password.isNotEmpty()     -> true
                // edit + blank → keep existing
                else                      -> false
            }
            if (shouldSavePassword) {
                HypervisorPasswordStore.storeAccountPassword(requireContext(), savedId, password)
            }
            Logger.i(TAG, if (existing == null) "Created virt identity id=$savedId ($name)"
                          else "Updated virt identity id=$savedId ($name)")
        }
    }

    private fun confirmDeleteVirtAccount(account: HypervisorAccount) {
        lifecycleScope.launch {
            val linked = try {
                app.database.hypervisorDao().getAllList().count { it.accountId == account.id }
            } catch (_: Exception) { 0 }

            val message = if (linked > 0) {
                resources.getQuantityString(R.plurals.identity_vm_cred_linked_hypervisors, linked, linked, account.name)
            } else {
                getString(R.string.identity_vm_cred_delete_message_fmt, account.name)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.identity_vm_cred_delete_title))
                .setMessage(message)
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    if (linked > 0) return@setPositiveButton
                    lifecycleScope.launch {
                        app.database.hypervisorAccountDao().delete(account)
                        // H6 — Long PK is device-local; tombstone by natural key.
                        TombstoneRecorder.record(app, TombstoneRecorder.HYPERVISOR_ACCOUNT, TombstoneRecorder.naturalKey(account))
                        HypervisorPasswordStore.clearAccountPassword(requireContext(), account.id)
                        // OCI accounts additionally own an API private key PEM and
                        // (optionally) its passphrase under their own aliases. Drop
                        // those too, or a reused account id would expose the previous
                        // owner's private key.
                        HypervisorPasswordStore.clearOciAccountSecrets(requireContext(), account.id)
                        Logger.i(TAG, "Deleted VM credential id=${account.id} (${account.name})")
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    companion object {
        private const val TAG = "AuthVmsFragment"
        fun newInstance() = AuthVmsFragment()
    }
}
