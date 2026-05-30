package io.github.tabssh.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
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
import io.github.tabssh.cloud.AwsEc2Client
import io.github.tabssh.cloud.AzureVmClient
import io.github.tabssh.cloud.CloudProvider
import io.github.tabssh.cloud.CloudProviderType
import io.github.tabssh.cloud.DigitalOceanClient
import io.github.tabssh.cloud.GcpComputeClient
import io.github.tabssh.cloud.HetznerClient
import io.github.tabssh.cloud.ImportCandidate
import io.github.tabssh.cloud.LinodeClient
import io.github.tabssh.cloud.OciCloudClient
import io.github.tabssh.cloud.VultrClient
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.databinding.ItemCloudAccountBinding
import io.github.tabssh.storage.database.SystemGroupHelper
import io.github.tabssh.storage.database.entities.CloudAccount
import io.github.tabssh.ui.activities.TabTerminalActivity
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
            onItemClick = { showAccountHosts(it) }
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
                showOciCredentialsDialog(name)
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
     * Multi-field credentials dialog for OCI accounts. Collects tenancy OCID,
     * user OCID, fingerprint, region, compartment (optional), PEM private key,
     * and an optional passphrase, then packs them as JSON for [saveAccount].
     */
    private fun showOciCredentialsDialog(accountName: String) {
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

        fun field(hint: String, multiLine: Boolean = false, password: Boolean = false): TextInputEditText {
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
            }
            til.addView(edit)
            ll.addView(til)
            return edit
        }

        val editTenancy     = field("Tenancy OCID  (ocid1.tenancy.oc1.…)")
        val editUser        = field("User OCID  (ocid1.user.oc1.…)")
        val editFingerprint = field("API key fingerprint  (aa:bb:cc:…)")
        val editRegion      = field("Region  (e.g. us-ashburn-1)")
        val editCompartment = field("Compartment OCID  (optional — blank = root)")
        val editPem         = field("RSA private key PEM", multiLine = true)
        val editPassphrase  = field("Key passphrase  (optional)", password = true)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("OCI credentials — $accountName")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ -> /* overridden below */ }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()

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
            saveAccount(accountName, CloudProviderType.OCI, tokenJson)
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
            val provider: CloudProvider = when (CloudProviderType.fromTag(account.provider)) {
                CloudProviderType.DIGITALOCEAN -> DigitalOceanClient()
                CloudProviderType.HETZNER      -> HetznerClient()
                CloudProviderType.LINODE       -> LinodeClient()
                CloudProviderType.VULTR        -> VultrClient()
                CloudProviderType.AWS          -> AwsEc2Client()
                CloudProviderType.GCP          -> GcpComputeClient()
                CloudProviderType.AZURE        -> AzureVmClient()
                CloudProviderType.OCI          -> OciCloudClient()
                null -> {
                    if (!isAdded) return@launch
                    Toast.makeText(requireContext(), "Unknown provider: ${account.provider}", Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            val candidates = try {
                withContext(Dispatchers.IO) { provider.fetchInventory(token, account.name) }
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
                            lastCount = candidates.size
                        )
                    )
                }
            } catch (_: Exception) {}
            if (!isAdded) return@launch
            showImportPicker(account, candidates)
        }
    }

    private fun showImportPicker(account: CloudAccount, candidates: List<ImportCandidate>) {
        if (!isAdded) return
        if (candidates.isEmpty()) {
            Toast.makeText(requireContext(), "${account.name}: 0 hosts found", Toast.LENGTH_SHORT).show()
            return
        }
        val labels  = candidates.map { "${it.profile.getDisplayName()} (${it.sourceLabel})" }.toTypedArray()
        val checked = BooleanArray(candidates.size) { true }
        AlertDialog.Builder(requireContext())
            .setTitle("${account.name}: pick to import (${candidates.size})")
            .setMultiChoiceItems(labels, checked) { _, idx, isChecked -> checked[idx] = isChecked }
            .setPositiveButton("Import") { _, _ ->
                importPicked(candidates.filterIndexed { i, _ -> checked[i] })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importPicked(picked: List<ImportCandidate>) {
        if (picked.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (inserted, updated) = withContext(Dispatchers.IO) {
                    val existing = app.database.connectionDao().getAllConnectionsList()
                    var ins = 0
                    var upd = 0
                    val cloudGroupId = SystemGroupHelper.getOrCreateSystemGroupId(
                        app.database, "cloud", "Cloud Instances", "cloud"
                    )
                    for (cand in picked) {
                        val src   = extractCloudSource(cand.profile.advancedSettings)
                        val match = existing.firstOrNull { e ->
                            e.host     == cand.profile.host     &&
                            e.port     == cand.profile.port     &&
                            e.username == cand.profile.username &&
                            extractCloudSource(e.advancedSettings) == src
                        }
                        if (match != null) {
                            app.database.connectionDao().updateConnection(
                                match.copy(
                                    name             = cand.profile.name,
                                    advancedSettings = cand.profile.advancedSettings,
                                    groupId          = cloudGroupId,
                                    modifiedAt       = System.currentTimeMillis()
                                )
                            )
                            upd++
                        } else {
                            app.database.connectionDao().insertConnection(
                                cand.profile.copy(groupId = cloudGroupId)
                            )
                            ins++
                        }
                    }
                    ins to upd
                }
                if (!isAdded) return@launch
                Toast.makeText(
                    requireContext(),
                    "Inserted $inserted, updated $updated",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Logger.e(TAG, "Bulk insert/update failed", e)
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAccountHosts(account: CloudAccount) {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            val allConnections = withContext(Dispatchers.IO) {
                app.database.connectionDao().getAllConnectionsList()
            }
            if (!isAdded) return@launch
            val accountSource = "${account.provider}:${account.name}"
            val hosts = allConnections.filter { conn ->
                extractCloudSource(conn.advancedSettings) == accountSource
            }
            if (hosts.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle(account.name)
                    .setMessage("No hosts imported yet. Use the refresh button to fetch and import hosts.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }
            val labels = hosts.map { "${it.getDisplayName()}  –  ${it.host}:${it.port}" }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("${account.name} · ${hosts.size} host${if (hosts.size == 1) "" else "s"}")
                .setItems(labels) { _, idx ->
                    startActivity(
                        TabTerminalActivity.createIntent(
                            requireContext(), hosts[idx], autoConnect = true
                        )
                    )
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun confirmDelete(account: CloudAccount) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${account.name}?")
            .setMessage("Removes this cloud account record and its stored token. Imported connections stay.")
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

    /** Pull the cloud_source field out of the advancedSettings JSON, or null. */
    private fun extractCloudSource(advanced: String?): String? {
        if (advanced.isNullOrBlank()) return null
        return try {
            org.json.JSONObject(advanced).optString("cloud_source").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

}
