package io.github.tabssh.ui.fragments

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioGroup
import android.widget.TextView
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
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.keys.GenerateResult
import io.github.tabssh.crypto.keys.ImportResult
import io.github.tabssh.crypto.keys.KeyType
import io.github.tabssh.crypto.storage.HypervisorPasswordStore
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.hypervisor.oci.OciConfigParser
import io.github.tabssh.hypervisor.oci.OciConfigProfile
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorAccount
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.VncIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import io.github.tabssh.ui.adapters.HypervisorAccountAdapter
import io.github.tabssh.ui.adapters.IdentityAdapter
import io.github.tabssh.ui.adapters.StoredKeyAdapter
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.showError
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
    private lateinit var vncIdentityAdapter: VncIdentityAdapter

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
                    if (validateCert(text)) setKeyCert(key, text, "Certificate attached")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to read cert file", e)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    Toast.makeText(requireContext(), "Failed to read certificate: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Opens a file picker for an OCI PEM private key in the virt identity dialog. */
    private val ociPemLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        // Capture context on the Main thread before switching to IO
        val ctx = context ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = ctx.contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    ociDialogPem = text
                    ociDialogPemCallback?.invoke(text)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to read PEM file", e)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    Toast.makeText(requireContext(), "Failed to read PEM file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Opens a file picker for an OCI ~/.oci/config in the virt identity dialog. */
    private val ociConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        // Capture context on the Main thread before switching to IO
        val ctx = context ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = ctx.contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val profiles = OciConfigParser.parse(text).filter { !it.usesSessionToken }
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    when {
                        profiles.isEmpty() -> Toast.makeText(
                            requireContext(),
                            "No API-key profiles found in that config (session-token profiles are not supported)",
                            Toast.LENGTH_LONG
                        ).show()
                        profiles.size == 1 -> ociDialogConfigCallback?.invoke(profiles[0])
                        else -> showOciProfilePickerDialog(profiles)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to read OCI config file", e)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    Toast.makeText(requireContext(), "Failed to read .oci/config: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── State bridging SAF results back to dialog callbacks ──────────────────

    /** The key currently awaiting a certificate file from [attachCertLauncher]. */
    private var pendingCertKey: StoredKey? = null

    /** PEM text staged in the virt identity dialog — reset on each open. */
    private var ociDialogPem: String = ""

    /**
     * Callback that updates the dialog's PEM status text when a key is
     * loaded (paste or file import). Captured from the dialog's closure;
     * set to null when the dialog closes.
     */
    private var ociDialogPemCallback: ((String) -> Unit)? = null

    /**
     * Callback that populates the dialog's OCI fields from a parsed
     * profile. Set from the dialog closure; null when not showing.
     */
    private var ociDialogConfigCallback: ((OciConfigProfile) -> Unit)? = null

    // ── Fragment lifecycle ────────────────────────────────────────────────────

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
        setupVncIdentitiesSection(view)
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

    // ─── VNC Identities ─────────────────────────────────────────────────────

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
    }

    private fun updateVncEmptyState(view: View, count: Int) {
        val empty = count == 0
        view.findViewById<View>(R.id.recycler_vnc_identities).visibility = if (empty) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.text_vnc_identities_empty).visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun showVncIdentityDialog(existing: VncIdentity?) {
        val ctx = requireContext()
        val isEditing = existing != null

        val dialogView = android.view.LayoutInflater.from(ctx).inflate(
            android.R.layout.simple_list_item_2, null, false
        )
        // Build fields manually since we don't have a dedicated dialog layout
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        fun makeEditText(hint: String, value: String? = null, inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): android.widget.EditText {
            return android.widget.EditText(ctx).apply {
                this.hint = hint
                setText(value ?: "")
                this.inputType = inputType
                val params = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = 16
                layoutParams = params
            }
        }
        val editName = makeEditText("Name (required)", existing?.name)
        val editUsername = makeEditText("Username (for VeNCrypt Plain)", existing?.username)
        val editDescription = makeEditText("Description", existing?.description)
        val editPassword = makeEditText(
            if (isEditing) "Password (blank = keep existing)" else "Password",
            null,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        layout.addView(editName)
        layout.addView(editUsername)
        layout.addView(editDescription)
        layout.addView(editPassword)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isEditing) "Edit VNC Identity" else "Add VNC Identity")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = editName.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(ctx, "Name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val now = System.currentTimeMillis()
                val id = existing?.id ?: java.util.UUID.randomUUID().toString()
                val identity = VncIdentity(
                    id = id,
                    name = name,
                    username = editUsername.text.toString().trim().takeIf { it.isNotBlank() },
                    description = editDescription.text.toString().trim().takeIf { it.isNotBlank() },
                    createdAt = existing?.createdAt ?: now,
                    modifiedAt = now
                )
                val password = editPassword.text.toString()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.database.vncIdentityDao().insert(identity) }
                    if (password.isNotBlank()) {
                        try {
                            app.securePasswordManager.storePassword(
                                "vnc_identity_$id",
                                password,
                                io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.ENCRYPTED
                            )
                        } catch (e: Exception) {
                            Logger.w(TAG, "Failed to store VNC identity password: ${e.message}")
                        }
                    }
                    Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteVncIdentity(identity: VncIdentity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete VNC identity?")
            .setMessage("\"${identity.name}\" will be removed. Any VNC hosts using it will lose their credential link.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.database.vncIdentityDao().deleteById(identity.id) }
                    try {
                        app.securePasswordManager.clearPassword("vnc_identity_${identity.id}")
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to clear VNC identity password: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── VNC Identity adapter ────────────────────────────────────────────────

    inner class VncIdentityAdapter(
        private val onEdit: (VncIdentity) -> Unit,
        private val onDelete: (VncIdentity) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<VncIdentityAdapter.VH>() {

        private var items: List<VncIdentity> = emptyList()

        fun submit(list: List<VncIdentity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vnc_identity, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val identity = items[position]
            holder.name.text = identity.name
            if (!identity.description.isNullOrBlank()) {
                holder.description.visibility = View.VISIBLE
                holder.description.text = identity.description
            } else if (!identity.username.isNullOrBlank()) {
                holder.description.visibility = View.VISIBLE
                holder.description.text = "Username: ${identity.username}"
            } else {
                holder.description.visibility = View.GONE
            }
            holder.btnEdit.setOnClickListener { onEdit(identity) }
            holder.btnDelete.setOnClickListener { onDelete(identity) }
        }

        inner class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val name: android.widget.TextView = view.findViewById(R.id.text_name)
            val description: android.widget.TextView = view.findViewById(R.id.text_description)
            val btnEdit: android.widget.ImageButton = view.findViewById(R.id.btn_edit)
            val btnDelete: android.widget.ImageButton = view.findViewById(R.id.btn_delete)
        }
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
                    app.database.vncIdentityDao().getAllIdentities().collect { list ->
                        vncIdentityAdapter.submit(list)
                        view?.let { updateVncEmptyState(it, list.size) }
                        Logger.d(TAG, "Loaded ${list.size} VNC identities")
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
            AuthType.PUBLIC_KEY -> 1
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

    /**
     * Show the create / edit dialog for a [HypervisorAccount].
     * The dialog uses [R.layout.dialog_edit_virt_identity] which has a
     * RadioGroup type selector at the top: "Password" or "OCI API Key".
     * The appropriate section is shown / hidden when the user toggles.
     *
     * OCI PEM and passphrase are staged in [ociDialogPem] /
     * [ociDialogPemCallback] / [ociDialogConfigCallback] so the SAF
     * launchers (field initializers) can deliver results to the dialog
     * without needing a direct reference to it.
     */
    private fun showVirtAccountDialog(existing: HypervisorAccount?) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_virt_identity, null)

        val radioGroupType = dialogView.findViewById<RadioGroup>(R.id.radio_group_type)
        val sectionPassword = dialogView.findViewById<View>(R.id.section_password)
        val sectionOci = dialogView.findViewById<View>(R.id.section_oci)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.edit_name)

        // Password section
        val editUsername = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_password)
        val editRealm = dialogView.findViewById<TextInputEditText>(R.id.edit_realm)

        // OCI section
        val editOciTenancy = dialogView.findViewById<TextInputEditText>(R.id.edit_oci_tenancy)
        val editOciUser = dialogView.findViewById<TextInputEditText>(R.id.edit_oci_user)
        val dropdownOciRegion = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_oci_region)
        val editOciFingerprint = dialogView.findViewById<TextInputEditText>(R.id.edit_oci_fingerprint)
        val editOciCompartment = dialogView.findViewById<TextInputEditText>(R.id.edit_oci_compartment)
        val buttonPastePem = dialogView.findViewById<MaterialButton>(R.id.button_paste_pem)
        val buttonImportPem = dialogView.findViewById<MaterialButton>(R.id.button_import_pem)
        val buttonImportOciConfig = dialogView.findViewById<MaterialButton>(R.id.button_import_oci_config)
        val textPemStatus = dialogView.findViewById<TextView>(R.id.text_pem_status)
        val editOciPassphrase = dialogView.findViewById<TextInputEditText>(R.id.edit_oci_passphrase)

        // Seed region autocomplete
        dropdownOciRegion.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, REGION_SEED)
        )

        // Section visibility driven by type radio
        radioGroupType.setOnCheckedChangeListener { _, checkedId ->
            sectionPassword.visibility = if (checkedId == R.id.radio_password) View.VISIBLE else View.GONE
            sectionOci.visibility = if (checkedId == R.id.radio_oci) View.VISIBLE else View.GONE
        }

        // Reset staged PEM state for this dialog session
        ociDialogPem = ""
        ociDialogPemCallback = null
        ociDialogConfigCallback = null

        // Pre-fill fields if editing
        existing?.let { acc ->
            editName.setText(acc.name)
            val isOci = acc.authType == "oci_api_key"
            if (isOci) {
                dialogView.findViewById<android.widget.RadioButton>(R.id.radio_oci).isChecked = true
                sectionPassword.visibility = View.GONE
                sectionOci.visibility = View.VISIBLE
                editOciTenancy.setText(acc.ociTenancyOcid ?: "")
                editOciUser.setText(acc.ociUserOcid ?: "")
                dropdownOciRegion.setText(acc.ociRegion ?: "", false)
                editOciFingerprint.setText(acc.ociFingerprint ?: "")
                editOciCompartment.setText(acc.ociCompartmentOcid ?: "")
                // Load PEM status from Keystore asynchronously — just show
                // whether a key exists; never surface the PEM itself.
                lifecycleScope.launch(Dispatchers.IO) {
                    val hasPem = HypervisorPasswordStore
                        .retrieveOciAccountKey(requireContext(), acc.id)?.isNotBlank() == true
                    withContext(Dispatchers.Main) {
                        if (hasPem) {
                            textPemStatus.text = "PEM key loaded — choose a new file to replace"
                            ociDialogPem = EXISTING_PEM_SENTINEL
                        } else {
                            textPemStatus.text = "No key loaded"
                        }
                    }
                }
            } else {
                editUsername.setText(acc.username)
                editRealm.setText(acc.realm ?: "")
                // Show mask if a password is stored, async
                lifecycleScope.launch(Dispatchers.IO) {
                    val hasPw = HypervisorPasswordStore
                        .retrieveAccountPassword(requireContext(), acc.id)?.isNotBlank() == true
                    withContext(Dispatchers.Main) {
                        if (hasPw) {
                            editPassword.setText(PASSWORD_MASK)
                            editPassword.hint = "Password set — leave to keep, or type to replace"
                        }
                    }
                }
            }
        }

        // Wire up PEM callback — updates status text and stores the PEM
        ociDialogPemCallback = { pem ->
            ociDialogPem = pem
            val looksValid = pem.contains("PRIVATE KEY")
            textPemStatus.text = if (looksValid)
                "PEM key loaded (${pem.lines().size} lines)"
            else
                "⚠ Doesn't look like a private key — verify format"
        }

        // Wire up .oci/config profile callback — populates the five OCI fields.
        // Auto-fills the identity name from the section header (e.g. [DEFAULT] → DEFAULT)
        // when the name field is blank so the user doesn't have to type it manually.
        ociDialogConfigCallback = { profile ->
            if (editName.text.toString().isBlank()) {
                editName.setText(profile.name)
            }
            editOciTenancy.setText(profile.tenancyOcid ?: "")
            editOciUser.setText(profile.userOcid ?: "")
            dropdownOciRegion.setText(profile.region ?: "", false)
            editOciFingerprint.setText(profile.fingerprint ?: "")
            Toast.makeText(
                requireContext(),
                "Profile \"${profile.name}\" imported — add the PEM key separately",
                Toast.LENGTH_LONG
            ).show()
        }

        // PEM paste: try clipboard first; fall back to manual paste dialog
        buttonPastePem.setOnClickListener {
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip?.getItemAt(0)
                ?.coerceToText(requireContext())?.toString().orEmpty()
            if (clip.contains("PRIVATE KEY")) {
                ociDialogPemCallback?.invoke(clip)
                Toast.makeText(requireContext(), "PEM key pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                showPasteOciPemDialog()
            }
        }
        buttonImportPem.setOnClickListener { ociPemLauncher.launch(arrayOf("*/*")) }
        buttonImportOciConfig.setOnClickListener { ociConfigLauncher.launch(arrayOf("*/*")) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) "New Virtualization Identity" else "Edit Identity")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = editName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), "Name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val isOciSelected = radioGroupType.checkedRadioButtonId == R.id.radio_oci
                if (isOciSelected) {
                    saveOciAccount(
                        existing, name,
                        editOciTenancy, editOciUser, dropdownOciRegion,
                        editOciFingerprint, editOciCompartment, editOciPassphrase
                    )
                } else {
                    savePasswordAccount(existing, name, editUsername, editPassword, editRealm)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Null callbacks so the launchers don't call into a dismissed dialog
                ociDialogPemCallback = null
                ociDialogConfigCallback = null
            }
            .show()
    }

    /** Show a multi-line paste dialog for OCI PEM keys. */
    private fun showPasteOciPemDialog() {
        val edit = android.widget.EditText(requireContext()).apply {
            hint = "Paste PEM private key (-----BEGIN … PRIVATE KEY-----)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            maxLines = 20
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Paste PEM Private Key")
            .setView(edit)
            .setPositiveButton("Use Key") { _, _ ->
                val pem = edit.text.toString().trim()
                if (pem.isNotBlank()) ociDialogPemCallback?.invoke(pem)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Show a profile picker when the .oci/config contains multiple profiles. */
    private fun showOciProfilePickerDialog(profiles: List<OciConfigProfile>) {
        val names = profiles.map { p ->
            p.name + if (!p.isComplete) " (incomplete)" else ""
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Choose OCI Profile")
            .setItems(names) { _, which ->
                ociDialogConfigCallback?.invoke(profiles[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Persist a password-type [HypervisorAccount]. */
    private fun savePasswordAccount(
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
            Toast.makeText(requireContext(), "Username is required", Toast.LENGTH_SHORT).show()
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
                password == PASSWORD_MASK -> false   // user left the mask unchanged
                existing == null          -> true    // new account
                password.isNotEmpty()     -> true    // user typed a replacement
                else                      -> false   // edit + blank → keep existing
            }
            if (shouldSavePassword) {
                HypervisorPasswordStore.storeAccountPassword(requireContext(), savedId, password)
            }
            Logger.i(TAG, if (existing == null) "Created virt identity id=$savedId ($name)"
                          else "Updated virt identity id=$savedId ($name)")
        }
    }

    /** Persist an OCI API-key-type [HypervisorAccount]. */
    private fun saveOciAccount(
        existing: HypervisorAccount?,
        name: String,
        editOciTenancy: TextInputEditText,
        editOciUser: TextInputEditText,
        dropdownOciRegion: AutoCompleteTextView,
        editOciFingerprint: TextInputEditText,
        editOciCompartment: TextInputEditText,
        editOciPassphrase: TextInputEditText
    ) {
        val tenancy = editOciTenancy.text?.toString()?.trim().orEmpty()
        val user = editOciUser.text?.toString()?.trim().orEmpty()
        val region = dropdownOciRegion.text?.toString()?.trim().orEmpty()
        val fingerprint = editOciFingerprint.text?.toString()?.trim().orEmpty()
        val compartment = editOciCompartment.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val passphrase = editOciPassphrase.text?.toString().orEmpty()

        if (tenancy.isBlank() || user.isBlank() || region.isBlank() || fingerprint.isBlank()) {
            Toast.makeText(
                requireContext(),
                "Tenancy, User OCID, Region, and Fingerprint are required",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        // Require a PEM key for new accounts; for edits the sentinel means
        // the existing Keystore key should be kept.
        if (existing == null && (ociDialogPem.isBlank() || ociDialogPem == EXISTING_PEM_SENTINEL)) {
            Toast.makeText(requireContext(), "A PEM private key is required", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val savedId: Long = if (existing == null) {
                app.database.hypervisorAccountDao().insert(
                    HypervisorAccount(
                        name = name,
                        authType = "oci_api_key",
                        ociTenancyOcid = tenancy,
                        ociUserOcid = user,
                        ociRegion = region,
                        ociFingerprint = fingerprint,
                        ociCompartmentOcid = compartment
                    )
                )
            } else {
                app.database.hypervisorAccountDao().update(
                    existing.copy(
                        name = name,
                        ociTenancyOcid = tenancy,
                        ociUserOcid = user,
                        ociRegion = region,
                        ociFingerprint = fingerprint,
                        ociCompartmentOcid = compartment,
                        modifiedAt = System.currentTimeMillis()
                    )
                )
                existing.id
            }
            // Only replace the stored PEM when a new one was loaded
            if (ociDialogPem.isNotBlank() && ociDialogPem != EXISTING_PEM_SENTINEL) {
                HypervisorPasswordStore.storeOciAccountKey(requireContext(), savedId, ociDialogPem)
            }
            if (passphrase.isNotEmpty()) {
                HypervisorPasswordStore.storeOciAccountPassphrase(requireContext(), savedId, passphrase)
            }
            Logger.i(TAG, if (existing == null) "Created OCI identity id=$savedId ($name)"
                          else "Updated OCI identity id=$savedId ($name)")
        }
    }

    private fun confirmDeleteVirtAccount(account: HypervisorAccount) {
        lifecycleScope.launch {
            val linked = try {
                app.database.hypervisorDao().getAllList().count { it.accountId == account.id }
            } catch (_: Exception) { 0 }

            val secretLabel = if (account.authType == "oci_api_key") "API key" else "password"
            val message = if (linked > 0) {
                "$linked hypervisor${if (linked == 1) "" else "s"} still link to \"${account.name}\". " +
                "Unlink them in their edit screen first."
            } else {
                "Delete \"${account.name}\"?\n\nThe stored $secretLabel will be cleared from the Keystore."
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Virtualization Identity")
                .setMessage(message)
                .setPositiveButton("Delete") { _, _ ->
                    if (linked > 0) return@setPositiveButton
                    lifecycleScope.launch {
                        app.database.hypervisorAccountDao().delete(account)
                        if (account.authType == "oci_api_key") {
                            HypervisorPasswordStore.clearOciAccountSecrets(requireContext(), account.id)
                        } else {
                            HypervisorPasswordStore.clearAccountPassword(requireContext(), account.id)
                        }
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
            .setTitle("Add SSH Key")
            .setItems(arrayOf("📥 Import from File", "📋 Paste Key", "🔑 Generate Key")) { _, which ->
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
            .setItems(arrayOf("ℹ️ Details & More…", "🗑️ Delete Key")) { _, which ->
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
            "Certificate: ✓ attached ($firstField)"
        } ?: "Certificate: — none"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(key.name)
            .setMessage(
                "Type: ${key.keyType}\n" +
                "Fingerprint: ${key.fingerprint}\n" +
                "Created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(key.createdAt))}\n" +
                (if (!key.comment.isNullOrEmpty()) "Comment: ${key.comment}\n" else "") +
                certInfo
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("More…") { _, _ -> showMoreActionsDialog(key) }
            .setNegativeButton("Delete") { _, _ -> confirmDeleteSshKey(key) }
            .show()
    }

    private fun showMoreActionsDialog(key: StoredKey) {
        val items = mutableListOf(
            "📋 Copy Public Key",
            "⬆️ Install on server…",
            "Rename",
            "Attach certificate (paste)…",
            "Attach certificate (file)…"
        )
        if (key.certificate != null) items += "Remove certificate"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(key.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "📋 Copy Public Key" -> copyPublicKeyToClipboard(key)
                    "⬆️ Install on server…" -> showInstallOnServerDialog(key)
                    "Rename" -> showRenameKeyDialog(key)
                    "Attach certificate (paste)…" -> showPasteCertDialog(key)
                    "Attach certificate (file)…" -> { pendingCertKey = key; attachCertLauncher.launch(arrayOf("*/*")) }
                    "Remove certificate" -> setKeyCert(key, null, "Certificate removed")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInstallOnServerDialog(key: StoredKey) {
        lifecycleScope.launch(Dispatchers.IO) {
            val sshConnections = app.database.connectionDao().getAllConnectionsList()
                .filter { it.protocol == "ssh" }
            if (sshConnections.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "No SSH connections saved", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val names = sshConnections
                .map { "${it.name} (${it.username}@${it.host}:${it.port})" }
                .toTypedArray()
            withContext(Dispatchers.Main) {
                var selectedIdx = 0
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Install \"${key.name}\" on server")
                    .setSingleChoiceItems(names, 0) { _, i -> selectedIdx = i }
                    .setPositiveButton("Install") { _, _ ->
                        installKeyOnServer(key, sshConnections[selectedIdx])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun installKeyOnServer(key: StoredKey, profile: ConnectionProfile) {
        lifecycleScope.launch(Dispatchers.IO) {
            val progressDialog = withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Installing key…")
                    .setMessage("Connecting to ${profile.host}…")
                    .setCancelable(false)
                    .show()
            }
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
                            "Key is already installed on ${profile.host}"
                        output.contains("installed") ->
                            "Key installed on ${profile.host}"
                        else ->
                            "Key installation completed on ${profile.host}"
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Logger.e("IdentitiesFragment", "installKeyOnServer failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "Failed to install key: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
                scope.cancel()
            }
        }
    }

    private fun copyPublicKeyToClipboard(key: StoredKey) {
        lifecycleScope.launch(Dispatchers.IO) {
            val text = app.keyStorage.getPublicKeyText(key.keyId)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (text == null) {
                    Toast.makeText(requireContext(), "Failed to read public key", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val clipboard = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("SSH public key", text)
                )
                Toast.makeText(requireContext(), "Public key copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Show a paste dialog pre-filled with clipboard if it looks like a cert. */
    private fun showPasteCertDialog(key: StoredKey) {
        val edit = android.widget.EditText(requireContext()).apply {
            hint = "Paste *-cert.pub line (e.g. ssh-rsa-cert-v01@openssh.com AAAA…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 8
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Attach OpenSSH Certificate")
            .setView(edit)
            .setPositiveButton("Attach") { _, _ ->
                val cert = edit.text.toString().trim()
                if (validateCert(cert)) setKeyCert(key, cert, "Certificate attached")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun validateCert(cert: String): Boolean {
        if (!cert.contains("-cert-v01@openssh.com")) {
            showError(
                "Doesn't look like an OpenSSH certificate (missing '-cert-v01@openssh.com').",
                "Invalid Certificate"
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
                Logger.e(TAG, "Failed to update certificate", e)
                showError("Failed to update certificate: ${e.message}", "Error")
            }
        }
    }

    private fun showRenameKeyDialog(key: StoredKey) {
        val edit = android.widget.EditText(requireContext()).apply {
            setText(key.name)
            hint = "Enter new name"
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename SSH Key")
            .setView(edit)
            .setPositiveButton("Rename") { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isNotBlank() && newName != key.name) renameKey(key, newName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameKey(key: StoredKey, newName: String) {
        lifecycleScope.launch {
            try {
                app.database.keyDao().updateKey(key.copy(name = newName))
                Toast.makeText(requireContext(), "Key renamed to \"$newName\"", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to rename key", e)
                showError("Failed to rename key: ${e.message}", "Rename Error")
            }
        }
    }

    private fun showKeyPasteDialog() {
        val edit = android.widget.EditText(requireContext()).apply {
            hint = "Paste your private key here (PEM format)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 10
            maxLines = 20
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Paste SSH Private Key")
            .setView(edit)
            .setPositiveButton("Next") { _, _ ->
                val content = edit.text.toString()
                if (content.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val preview = app.keyStorage.previewKey(content)
                        val defaultName = preview.comment.takeIf { it.isNotBlank() } ?: "Pasted Key"
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
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showKeyGenerateDialog() {
        val keyTypes = arrayOf("RSA 2048", "RSA 4096", "ECDSA P-256", "ECDSA P-384", "Ed25519")
        var selectedType = 4 // Default to Ed25519
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Generate SSH Key")
            .setSingleChoiceItems(keyTypes, selectedType) { _, which -> selectedType = which }
            .setPositiveButton("Next") { _, _ ->
                val nameEdit = android.widget.EditText(requireContext()).apply {
                    hint = "Key name (e.g. my-server-key)"
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Key Name")
                    .setView(nameEdit)
                    .setPositiveButton("Generate") { _, _ ->
                        val keyName = nameEdit.text.toString().trim().ifBlank { "generated-key" }
                        val (type, size) = when (selectedType) {
                            0 -> KeyType.RSA to 2048
                            1 -> KeyType.RSA to 4096
                            2 -> KeyType.ECDSA to 256
                            3 -> KeyType.ECDSA to 384
                            else -> KeyType.ED25519 to 256
                        }
                        generateKey(type, size, keyName)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importKeyFromFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = requireContext().contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: return@launch
                val display = resolveDisplayName(uri) ?: uri.lastPathSegment ?: "Imported Key"
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
                Logger.e(TAG, "Failed to read key file", e)
                withContext(Dispatchers.Main) {
                    showError("Failed to read key file: ${e.message}", "Import Error")
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
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }

        val nameEdit = android.widget.EditText(ctx).apply {
            setText(defaultName)
            setSelection(text.length)
            hint = "Display name"
        }
        val nameLabel = android.widget.TextView(ctx).apply { text = "Name (shown in key list)" }

        val aliasEdit = android.widget.EditText(ctx).apply {
            setText(defaultAlias)
            setSelection(text.length)
            hint = "SSH alias (e.g. id_ed25519)"
        }
        val aliasLabel = android.widget.TextView(ctx).apply {
            text = "Alias (matches IdentityFile in ~/.ssh/config)"
        }

        layout.addView(nameLabel)
        layout.addView(nameEdit)
        layout.addView(aliasLabel)
        layout.addView(aliasEdit)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Import SSH Key")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val name = nameEdit.text.toString().trim().ifBlank { defaultName }
                val alias = aliasEdit.text.toString().trim().ifBlank { defaultAlias }
                onConfirm(name, alias)
            }
            .setNegativeButton("Cancel", null)
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
                            Toast.makeText(requireContext(), "SSH key imported", Toast.LENGTH_SHORT).show()
                        }
                        is ImportResult.Error -> {
                            if (result.message.contains("encrypted") &&
                                result.message.contains("passphrase")
                            ) {
                                showPassphraseDialog(keyContent, filename, keyAlias)
                            } else {
                                showError("Key import failed:\n\n${result.message}", "Import Failed")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Key import failed", e)
                withContext(Dispatchers.Main) {
                    showError("Key import failed: ${e.message}", "Import Error")
                }
            }
        }
    }

    private fun showPassphraseDialog(keyContent: String, filename: String, keyAlias: String? = null) {
        val edit = android.widget.EditText(requireContext()).apply {
            hint = "Enter passphrase"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Encrypted Key")
            .setMessage("This key is encrypted. Enter the passphrase to import it.")
            .setView(edit)
            .setPositiveButton("Import") { _, _ ->
                val passphrase = edit.text.toString()
                importKeyWithPassphrase(keyContent, filename, passphrase, keyAlias)
            }
            .setNegativeButton("Cancel", null)
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
                            Toast.makeText(requireContext(), "SSH key imported", Toast.LENGTH_SHORT).show()
                        }
                        is ImportResult.Error -> {
                            Logger.e(TAG, "Encrypted key import failed: ${result.message}")
                            showError("Import failed:\n\n${result.message}", "Import Failed")
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Encrypted key import failed", e)
                withContext(Dispatchers.Main) {
                    showError("Encrypted key import failed: ${e.message}", "Import Error")
                }
            }
        }
    }

    private fun generateKey(keyType: KeyType, keySize: Int, keyName: String) {
        Toast.makeText(requireContext(), "Generating SSH key…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = app.keyStorage.generateKeyPair(keyType, keySize, keyName)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is GenerateResult.Success ->
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Key Generated")
                                .setMessage("\"$keyName\" generated successfully.\n\nFingerprint:\n${result.fingerprint}")
                                .setPositiveButton("OK", null)
                                .show()
                        is GenerateResult.Error ->
                            showError("Failed to generate key:\n\n${result.message}", "Generation Failed")
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Key generation failed", e)
                withContext(Dispatchers.Main) {
                    showError("Key generation failed: ${e.message}", "Error")
                }
            }
        }
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
        private const val TAG = "IdentitiesFragment"

        /** Displayed in password fields for existing stored credentials. */
        private const val PASSWORD_MASK = "••••••••"

        /**
         * Sentinel stored in [ociDialogPem] when an edit dialog finds an
         * existing Keystore key. Signals "leave the stored PEM unchanged".
         */
        private const val EXISTING_PEM_SENTINEL = " existing "

        /** Seed list of OCI commercial regions. The AutoCompleteTextView
         *  allows the user to type a custom one (Oracle adds regions periodically). */
        private val REGION_SEED = arrayOf(
            "us-ashburn-1", "us-phoenix-1", "us-chicago-1", "us-sanjose-1",
            "ca-toronto-1", "ca-montreal-1",
            "sa-saopaulo-1", "sa-vinhedo-1", "sa-santiago-1",
            "uk-london-1", "uk-cardiff-1",
            "eu-frankfurt-1", "eu-amsterdam-1", "eu-zurich-1", "eu-stockholm-1",
            "eu-marseille-1", "eu-milan-1", "eu-madrid-1", "eu-paris-1",
            "me-jeddah-1", "me-dubai-1", "me-abudhabi-1",
            "ap-tokyo-1", "ap-osaka-1", "ap-seoul-1", "ap-sydney-1",
            "ap-melbourne-1", "ap-mumbai-1", "ap-hyderabad-1", "ap-singapore-1",
            "af-johannesburg-1", "il-jerusalem-1",
            "mx-queretaro-1", "mx-monterrey-1"
        )

        fun newInstance() = IdentitiesFragment()
    }
}
