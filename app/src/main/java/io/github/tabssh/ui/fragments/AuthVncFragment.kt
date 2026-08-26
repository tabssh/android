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
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.storage.database.entities.VncIdentity
import io.github.tabssh.ui.adapters.VncIdentityAdapter
import io.github.tabssh.ui.fragments.AuthConstants.PASSWORD_MASK
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/** Auth tab — VNC sub-tab: credentials for direct VNC and VeNCrypt connections. */
class AuthVncFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var vncIdentityAdapter: VncIdentityAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_auth_vnc, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = tabSSHApp

        setupVncIdentitiesSection(view)
        observeData()
    }

    private fun setupVncIdentitiesSection(view: View) {
        vncIdentityAdapter = VncIdentityAdapter(
            onEdit = { identity -> showVncIdentityDialog(identity) },
            onDelete = { identity -> confirmDeleteVncIdentity(identity) }
        )
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_vnc_identities)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = vncIdentityAdapter
        updateVncEmptyState(view, 0)

        view.findViewById<MaterialButton>(R.id.btn_add_vnc_identity).setOnClickListener {
            showVncIdentityDialog(null)
        }
        view.findViewById<MaterialButton>(R.id.button_vnc_identities_empty_cta).setOnClickListener {
            showVncIdentityDialog(null)
        }
    }

    private fun updateVncEmptyState(view: View, count: Int) {
        val empty = count == 0
        view.findViewById<View>(R.id.recycler_vnc_identities).visibility = if (empty) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.text_vnc_identities_empty).visibility = if (empty) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.button_vnc_identities_empty_cta).visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun showVncIdentityDialog(existing: VncIdentity?) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_vnc_identity, null)

        val nameInput     = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_name)
        val usernameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_username)
        val passwordInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_password)
        val descInput     = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_description)

        existing?.let { id ->
            nameInput.setText(id.name)
            usernameInput.setText(id.username ?: "")
            descInput.setText(id.description ?: "")
        }
        if (existing != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val hasPw = try {
                    app.securePasswordManager.retrievePassword("vnc_identity_${existing.id}")?.isNotBlank() == true
                } catch (_: Exception) { false }
                withContext(Dispatchers.Main) {
                    if (hasPw) {
                        passwordInput.setText(PASSWORD_MASK)
                        passwordInput.hint = getString(R.string.identity_password_set_hint)
                    }
                }
            }
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) getString(R.string.identity_vnc_add_title) else getString(R.string.identity_vnc_edit_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(ctx, getString(R.string.xcpng_name_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val now = System.currentTimeMillis()
                val id  = existing?.id ?: java.util.UUID.randomUUID().toString()
                val identity = VncIdentity(
                    id          = id,
                    name        = name,
                    username    = usernameInput.text.toString().trim().takeIf { it.isNotBlank() },
                    description = descInput.text.toString().trim().takeIf { it.isNotBlank() },
                    createdAt   = existing?.createdAt ?: now,
                    modifiedAt  = now
                )
                val password = passwordInput.text.toString()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.database.vncIdentityDao().insert(identity) }
                    val shouldSavePassword = when {
                        password == PASSWORD_MASK -> false
                        existing == null          -> password.isNotBlank()
                        password.isNotBlank()     -> true
                        else                      -> false
                    }
                    if (shouldSavePassword) {
                        try {
                            app.securePasswordManager.storePassword(
                                "vnc_identity_$id",
                                password,
                                SecurePasswordManager.StorageLevel.ENCRYPTED
                            )
                        } catch (e: Exception) {
                            Logger.w(TAG, "Failed to store VNC identity password: ${e.message}")
                        }
                    }
                    Toast.makeText(ctx, getString(R.string.remote_editor_saved), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmDeleteVncIdentity(identity: VncIdentity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.identity_vnc_delete_title))
            .setMessage(getString(R.string.pane_group_delete_message_fmt, identity.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        app.database.vncIdentityDao().deleteById(identity.id)
                        // H6 — record the deletion so it propagates and is not resurrected.
                        TombstoneRecorder.record(app, TombstoneRecorder.VNC_IDENTITY, identity.id)
                    }
                    try {
                        app.securePasswordManager.clearPassword("vnc_identity_${identity.id}")
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to clear VNC identity password: ${e.message}")
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.database.vncIdentityDao().getAllIdentities().collect { list ->
                        vncIdentityAdapter.submit(list)
                        view?.let { updateVncEmptyState(it, list.size) }
                        Logger.d(TAG, "Loaded ${list.size} VNC identities")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AuthVncFragment"
        fun newInstance() = AuthVncFragment()
    }
}
