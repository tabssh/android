package io.github.tabssh.ui.fragments

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.cloud.CloudProviderType
import io.github.tabssh.cloud.newClient
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.databinding.ItemCloudAccountBinding
import io.github.tabssh.storage.database.entities.CloudAccount
import io.github.tabssh.ui.activities.CloudAccountManagerActivity
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragment equivalent of CloudAccountsActivity — shown as the "Cloud" sub-tab
 * inside InfraFragment. Retains all the same business logic (add / refresh /
 * delete / import) without the standalone Activity chrome.
 */
class CloudAccountsFragment : Fragment() {

    companion object {
        private const val TAG = "CloudAccountsFragment"
        private val ROW_FORMATTER = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        fun newInstance() = CloudAccountsFragment()

        private fun providerIcon(tag: String) = when (tag) {
            "digitalocean" -> "🌊"
            "hetzner"      -> "🖥️"
            "linode"       -> "🟠"
            "vultr"        -> "🦅"
            "aws"          -> "🟡"
            "gcp"          -> "🔵"
            "azure"        -> "🔷"
            "oci"          -> "🔶"
            else           -> "☁️"
        }
    }

    private lateinit var app: TabSSHApplication
    private lateinit var adapter: CloudAccountAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View

    /**
     * Holds all transient OCI form state while the user navigates between
     * the credentials dialog and the SAF file pickers.
     */
    private data class PendingOciState(
        val accountName: String,
        /** null means this is a new account; non-null means update the existing row. */
        val existingAccount: CloudAccount?,
        val tenancy: String = "",
        val user: String = "",
        val fingerprint: String = "",
        val region: String = "",
        val compartment: String = "",
        val pem: String = "",
        val passphrase: String = ""
    )

    private var pendingOciState: PendingOciState? = null

    /**
     * SAF picker for the ~/.oci/config INI file.
     * Parses tenancy/user/fingerprint/region from the file and re-shows the
     * credentials dialog with those fields pre-filled. Does NOT read the PEM —
     * that requires a separate picker because the key_file path is not readable
     * on Android without explicit user grant.
     */
    private val ociConfigFilePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            // User pressed back or cancelled — put the dialog back so they can retry.
            if (isAdded) showOciCredentialsDialog()
            return@registerForActivityResult
        }
        val uri = result.data?.data
        if (uri == null) {
            if (isAdded) {
                Toast.makeText(requireContext(), "No file selected", Toast.LENGTH_LONG).show()
                showOciCredentialsDialog()
            }
            return@registerForActivityResult
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?.let { parseOciConfigIni(it) }
                } catch (e: Exception) {
                    Logger.e(TAG, "OCI config read failed", e)
                    null
                }
            }
            if (parsed == null) {
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Could not read OCI config file — check the file and try again", Toast.LENGTH_LONG).show()
                showOciCredentialsDialog()
                return@launch
            }
            // Merge parsed fields into state; preserve pem/passphrase/compartment already entered.
            pendingOciState = pendingOciState?.copy(
                tenancy     = parsed["tenancy"]     ?: pendingOciState?.tenancy.orEmpty(),
                user        = parsed["user"]        ?: pendingOciState?.user.orEmpty(),
                fingerprint = parsed["fingerprint"] ?: pendingOciState?.fingerprint.orEmpty(),
                region      = parsed["region"]      ?: pendingOciState?.region.orEmpty()
            )
            if (isAdded) showOciCredentialsDialog()
        }
    }

    /**
     * SAF picker for the RSA private key PEM file referenced by key_file in the
     * OCI config. Reads the file content as text and stores it in pendingOciState.
     */
    private val ociKeyFilePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            // User pressed back or cancelled — put the dialog back so they can retry.
            if (isAdded) showOciCredentialsDialog()
            return@registerForActivityResult
        }
        val uri = result.data?.data
        if (uri == null) {
            if (isAdded) {
                Toast.makeText(requireContext(), "No file selected", Toast.LENGTH_LONG).show()
                showOciCredentialsDialog()
            }
            return@registerForActivityResult
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val pem = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }?.trim()
                } catch (e: Exception) {
                    Logger.e(TAG, "OCI key file read failed", e)
                    null
                }
            }
            if (pem == null) {
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Could not read key file — check the file and try again", Toast.LENGTH_LONG).show()
                showOciCredentialsDialog()
                return@launch
            }
            pendingOciState = pendingOciState?.copy(pem = pem)
            if (isAdded) showOciCredentialsDialog()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_cloud_accounts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = requireActivity().application as TabSSHApplication

        recycler    = view.findViewById(R.id.recycler_accounts)
        emptyState  = view.findViewById(R.id.layout_empty_state)

        adapter = CloudAccountAdapter(
            onRefresh   = { refreshAccount(it) },
            onDelete    = { confirmDelete(it) },
            onEdit      = { showEditAccountDialog(it) },
            onItemClick = { startActivity(CloudAccountManagerActivity.createIntent(requireContext(), it)) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            showAddAccountDialog()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.cloudAccountDao().observeAll().collect { accounts ->
                    if (!isAdded) return@collect
                    adapter.submitList(accounts)
                    recycler.visibility    = if (accounts.isEmpty()) View.GONE  else View.VISIBLE
                    emptyState.visibility  = if (accounts.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    private inner class CloudAccountAdapter(
        private val onRefresh:   (CloudAccount) -> Unit,
        private val onDelete:    (CloudAccount) -> Unit,
        private val onEdit:      (CloudAccount) -> Unit,
        private val onItemClick: (CloudAccount) -> Unit
    ) : ListAdapter<CloudAccount, CloudAccountAdapter.ViewHolder>(AccountDiff) {

        inner class ViewHolder(val b: ItemCloudAccountBinding) :
            RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemCloudAccountBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val account = getItem(position)
            val b = holder.b
            val providerType = CloudProviderType.fromTag(account.provider)

            b.textProviderIcon.text = providerIcon(account.provider)
            b.textAccountName.text  = account.name
            b.textProviderDetail.text = buildString {
                append(providerType?.displayName ?: account.provider)
                if (account.lastCount > 0) append(" · ${account.lastCount} hosts")
            }
            b.textLastRefresh.text = if (account.lastRefreshAt > 0) {
                "Last sync: ${ROW_FORMATTER.format(Date(account.lastRefreshAt))}"
            } else {
                "Never synced"
            }

            b.switchEnabled.isChecked = true
            b.switchEnabled.setOnCheckedChangeListener(null)

            b.root.setOnClickListener      { onItemClick(account) }
            b.root.setOnLongClickListener  { onEdit(account); true }
            b.btnRefresh.setOnClickListener { onRefresh(account) }
            b.btnDelete.setOnClickListener  { onDelete(account) }
        }
    }

    private object AccountDiff : DiffUtil.ItemCallback<CloudAccount>() {
        override fun areItemsTheSame(old: CloudAccount, new: CloudAccount) = old.id == new.id
        override fun areContentsTheSame(old: CloudAccount, new: CloudAccount) = old == new
    }

    // ── Add account dialog ────────────────────────────────────────────────────

    private fun showAddAccountDialog() {
        if (!isAdded) return
        val ctx = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_cloud_account, null, false)

        val tilName      = dialogView.findViewById<TextInputLayout>(R.id.til_account_name)
        val editName     = dialogView.findViewById<TextInputEditText>(R.id.edit_account_name)
        val tilToken     = dialogView.findViewById<TextInputLayout>(R.id.til_api_token)
        val editToken    = dialogView.findViewById<TextInputEditText>(R.id.edit_api_token)
        val spinProvider = dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_provider)
        val tvTokenHelp  = dialogView.findViewById<TextView>(R.id.tv_token_help)

        val labels = CloudProviderType.entries.map { it.displayName }
        spinProvider.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, labels))
        spinProvider.setText(labels.first(), false)
        tvTokenHelp.text = CloudProviderType.entries.first().tokenHelp

        spinProvider.setOnItemClickListener { _, _, position, _ ->
            val pt = CloudProviderType.entries[position]
            val isOci = pt == CloudProviderType.OCI
            tilToken.visibility  = if (isOci) android.view.View.GONE else android.view.View.VISIBLE
            tvTokenHelp.text     = if (isOci) "Credentials are entered in the next step." else pt.tokenHelp
            tilToken.helperText  = if (isOci) "" else pt.tokenHelp
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Add cloud account")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ -> /* overridden below */ }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = editName.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                tilName.error = "Account name is required"
                return@setOnClickListener
            }
            val selectedLabel = spinProvider.text?.toString().orEmpty()
            val providerType  = CloudProviderType.entries
                .firstOrNull { it.displayName == selectedLabel }
                ?: CloudProviderType.entries.first()

            if (providerType == CloudProviderType.OCI) {
                dialog.dismiss()
                pendingOciState = PendingOciState(accountName = name, existingAccount = null)
                showOciCredentialsDialog()
                return@setOnClickListener
            }

            val token = editToken.text?.toString().orEmpty()
            if (token.isBlank()) {
                tilToken.error = "API token is required"
                return@setOnClickListener
            }
            dialog.dismiss()
            saveAccount(name, providerType, token)
        }
    }

    /**
     * Multi-field credentials dialog for OCI accounts. Reads all field values
     * from pendingOciState to pre-fill on re-entry (e.g. after a file picker
     * round-trip). Offers buttons to import the .oci/config INI file and the
     * separate PEM key file via SAF pickers.
     */
    private fun showOciCredentialsDialog() {
        val state = pendingOciState ?: return
        if (!isAdded) return
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density

        val scroll = android.widget.ScrollView(ctx)
        val ll = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(ll)

        fun field(hint: String, initial: String = "", multiLine: Boolean = false, password: Boolean = false): TextInputEditText {
            val til = TextInputLayout(ctx, null,
                com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                this.hint = hint
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * dp).toInt() }
            }
            val edit = TextInputEditText(til.context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                inputType = when {
                    multiLine -> android.text.InputType.TYPE_CLASS_TEXT or
                                 android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    password  -> android.text.InputType.TYPE_CLASS_TEXT or
                                 android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else      -> android.text.InputType.TYPE_CLASS_TEXT
                }
                if (!multiLine) setSingleLine(true)
                if (multiLine) { minLines = 3; maxLines = 12 }
                setText(initial)
            }
            til.addView(edit)
            ll.addView(til)
            return edit
        }

        val editTenancy     = field("Tenancy OCID  (ocid1.tenancy.oc1.…)",          initial = state.tenancy)
        val editUser        = field("User OCID  (ocid1.user.oc1.…)",                initial = state.user)
        val editFingerprint = field("API key fingerprint  (aa:bb:cc:…)",             initial = state.fingerprint)
        val editRegion      = field("Region  (e.g. us-ashburn-1)",                   initial = state.region)
        val editCompartment = field("Compartment OCID  (optional — blank = root)",   initial = state.compartment)
        val editPem         = field("RSA private key PEM", initial = state.pem,      multiLine = true)
        val editPassphrase  = field("Key passphrase  (optional)", initial = state.passphrase, password = true)

        /** Snapshot the current field values into pendingOciState before leaving the dialog. */
        fun snapshotFields() {
            pendingOciState = pendingOciState?.copy(
                tenancy     = editTenancy.text?.toString()?.trim().orEmpty(),
                user        = editUser.text?.toString()?.trim().orEmpty(),
                fingerprint = editFingerprint.text?.toString()?.trim().orEmpty(),
                region      = editRegion.text?.toString()?.trim().orEmpty(),
                compartment = editCompartment.text?.toString()?.trim().orEmpty(),
                pem         = editPem.text?.toString()?.trim().orEmpty(),
                passphrase  = editPassphrase.text?.toString().orEmpty()
            )
        }

        // Button to import tenancy/user/fingerprint/region from ~/.oci/config INI file.
        val btnImportConfig = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "Import .oci/config file…"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (4 * dp).toInt() }
        }
        ll.addView(btnImportConfig)

        // Button to import the RSA private key PEM content from the key file.
        val btnImportKey = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "Import .pem key file…"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (4 * dp).toInt() }
        }
        ll.addView(btnImportKey)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("OCI credentials — ${state.accountName}")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ -> /* overridden below */ }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()

        btnImportConfig.setOnClickListener {
            snapshotFields()
            dialog.dismiss()
            ociConfigFilePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            })
        }

        btnImportKey.setOnClickListener {
            snapshotFields()
            dialog.dismiss()
            ociKeyFilePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            })
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val tenancy     = editTenancy.text?.toString()?.trim().orEmpty()
            val user        = editUser.text?.toString()?.trim().orEmpty()
            val fingerprint = editFingerprint.text?.toString()?.trim().orEmpty()
            val region      = editRegion.text?.toString()?.trim().orEmpty()
            val compartment = editCompartment.text?.toString()?.trim().orEmpty()
            val pem         = editPem.text?.toString()?.trim().orEmpty()
            val passphrase  = editPassphrase.text?.toString().orEmpty()

            if (tenancy.isBlank() || user.isBlank() || fingerprint.isBlank() ||
                    region.isBlank() || pem.isBlank()) {
                Toast.makeText(ctx,
                    "Tenancy, User, Fingerprint, Region, and PEM key are required",
                    Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val tokenJson = org.json.JSONObject()
                .put("tenancy",     tenancy)
                .put("user",        user)
                .put("fingerprint", fingerprint)
                .put("region",      region)
                .put("compartment", compartment)
                .put("pem",         pem)
                .put("passphrase",  passphrase)
                .toString()

            dialog.dismiss()
            saveOrUpdateOciAccount(state.accountName, state.existingAccount, tokenJson)
        }
    }

    /**
     * Persists an OCI account. If existing is non-null the row is updated in
     * place (no duplicate UUID); otherwise a new account is inserted.
     *
     * DB and credential storage are kept in sync: if storePassword() fails for
     * any reason (Keystore unavailable, device has no screen lock, etc.) the DB
     * change is rolled back so we never have an account row with a missing token.
     */
    private fun saveOrUpdateOciAccount(name: String, existing: CloudAccount?, tokenJson: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (existing != null) {
                        app.database.cloudAccountDao().update(
                            existing.copy(name = name, modifiedAt = System.currentTimeMillis())
                        )
                        val stored = app.securePasswordManager.storePassword(
                            "cloud_token_${existing.id}", tokenJson,
                            SecurePasswordManager.StorageLevel.ENCRYPTED
                        )
                        if (!stored) {
                            // Roll back the name change so the record stays consistent.
                            app.database.cloudAccountDao().update(existing)
                            throw Exception("Credential storage failed — check device security settings (screen lock required)")
                        }
                    } else {
                        val account = CloudAccount(name = name, provider = CloudProviderType.OCI.tag)
                        app.database.cloudAccountDao().upsert(account)
                        val stored = app.securePasswordManager.storePassword(
                            "cloud_token_${account.id}", tokenJson,
                            SecurePasswordManager.StorageLevel.ENCRYPTED
                        )
                        if (!stored) {
                            // Roll back the DB insert so there is no orphaned account row.
                            app.database.cloudAccountDao().delete(account)
                            throw Exception("Credential storage failed — check device security settings (screen lock required)")
                        }
                    }
                }
                if (!isAdded) return@launch
                val verb = if (existing != null) "Updated" else "Saved"
                Toast.makeText(requireContext(), "$verb '$name'", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e(TAG, "OCI account save failed", e)
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Business logic ────────────────────────────────────────────────────────

    private fun saveAccount(name: String, provider: CloudProviderType, token: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val account = CloudAccount(name = name, provider = provider.tag)
            try {
                withContext(Dispatchers.IO) {
                    app.database.cloudAccountDao().upsert(account)
                    app.securePasswordManager.storePassword(
                        "cloud_token_${account.id}", token,
                        SecurePasswordManager.StorageLevel.ENCRYPTED
                    )
                }
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Saved '${account.name}'", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e(TAG, "Save cloud account failed", e)
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Fetches the live instance count from the provider and updates the account
     * row's lastRefreshAt / lastCount metadata. Browsing and connecting to
     * instances happens exclusively inside CloudAccountManagerActivity (tap the row).
     */
    private fun refreshAccount(account: CloudAccount) {
        if (!isAdded) return
        Toast.makeText(requireContext(), "Refreshing ${account.name}…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                app.securePasswordManager.retrievePassword("cloud_token_${account.id}")
            }
            if (token.isNullOrBlank()) {
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Token missing — re-add account", Toast.LENGTH_LONG).show()
                return@launch
            }
            val providerType = CloudProviderType.fromTag(account.provider)
            if (providerType == null) {
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Unknown provider: ${account.provider}", Toast.LENGTH_LONG).show()
                return@launch
            }
            val count = try {
                withContext(Dispatchers.IO) {
                    providerType.newClient().fetchInventory(token, account.name).size
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Inventory fetch failed", e)
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Refresh failed: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    app.database.cloudAccountDao().update(
                        account.copy(
                            lastRefreshAt = System.currentTimeMillis(),
                            lastCount     = count
                        )
                    )
                }
            } catch (_: Exception) {}
            if (!isAdded) return@launch
            Toast.makeText(
                requireContext(),
                "${account.name}: $count instance${if (count == 1) "" else "s"}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmDelete(account: CloudAccount) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${account.name}?")
            .setMessage("Removes this cloud account record and its stored token.")
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.cloudAccountDao().delete(account)
                            app.securePasswordManager.clearPassword("cloud_token_${account.id}")
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Delete failed", e)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Edit-account dialog. For OCI: loads stored credentials from secure storage
     * and re-shows the full OCI credentials dialog with all fields pre-filled so
     * the user can update any individual field without re-entering everything.
     * For non-OCI providers: allows renaming and optionally replacing the API token.
     */
    private fun showEditAccountDialog(account: CloudAccount) {
        if (!isAdded) return
        val providerType = CloudProviderType.fromTag(account.provider)

        if (providerType == CloudProviderType.OCI) {
            viewLifecycleOwner.lifecycleScope.launch {
                val tokenJson = withContext(Dispatchers.IO) {
                    app.securePasswordManager.retrievePassword("cloud_token_${account.id}")
                }
                val creds = tokenJson?.let {
                    try { org.json.JSONObject(it) } catch (_: Exception) { null }
                }
                pendingOciState = PendingOciState(
                    accountName     = account.name,
                    existingAccount = account,
                    tenancy         = creds?.optString("tenancy").orEmpty(),
                    user            = creds?.optString("user").orEmpty(),
                    fingerprint     = creds?.optString("fingerprint").orEmpty(),
                    region          = creds?.optString("region").orEmpty(),
                    compartment     = creds?.optString("compartment").orEmpty(),
                    pem             = creds?.optString("pem").orEmpty(),
                    passphrase      = creds?.optString("passphrase").orEmpty()
                )
                if (isAdded) showOciCredentialsDialog()
            }
            return
        }

        val ctx = requireContext()
        val dp  = resources.displayMetrics.density

        val scroll = android.widget.ScrollView(ctx)
        val ll = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(ll)

        fun addEditField(hint: String, initialText: String = ""): TextInputEditText {
            val til = TextInputLayout(ctx, null,
                com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                this.hint = hint
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * dp).toInt() }
            }
            val edit = TextInputEditText(til.context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setSingleLine(true)
                setText(initialText)
            }
            til.addView(edit)
            ll.addView(til)
            return edit
        }

        val editName  = addEditField("Account name", account.name)
        val editToken = addEditField("New API token (leave blank to keep existing)")

        AlertDialog.Builder(ctx)
            .setTitle("Edit: ${account.name}")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val newName  = editName.text?.toString()?.trim().orEmpty().ifBlank { account.name }
                val newToken = editToken.text?.toString().orEmpty()
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.cloudAccountDao().update(
                                account.copy(name = newName, modifiedAt = System.currentTimeMillis())
                            )
                            if (newToken.isNotBlank()) {
                                app.securePasswordManager.storePassword(
                                    "cloud_token_${account.id}", newToken,
                                    SecurePasswordManager.StorageLevel.ENCRYPTED
                                )
                            }
                        }
                        if (!isAdded) return@launch
                        Toast.makeText(requireContext(), "Updated '$newName'", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Edit account failed", e)
                        if (!isAdded) return@launch
                        Toast.makeText(requireContext(), "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Parse an OCI SDK config INI file and return a map of key→value for the
     * first profile (DEFAULT or first named section). Returns null if the text
     * contains no recognisable key=value pairs. The key_file entry is intentionally
     * excluded — its path is not readable on Android without a separate SAF grant.
     */
    private fun parseOciConfigIni(iniText: String): Map<String, String>? {
        val lines = iniText.lines()
        val values = mutableMapOf<String, String>()
        var inProfile = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inProfile = trimmed == "[DEFAULT]" || (!inProfile && values.isEmpty())
                continue
            }
            if (!inProfile) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val key   = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            // key_file is a filesystem path that cannot be opened on Android without a SAF grant
            if (key == "key_file") continue
            values[key] = value
        }
        return values.ifEmpty { null }
    }

}
