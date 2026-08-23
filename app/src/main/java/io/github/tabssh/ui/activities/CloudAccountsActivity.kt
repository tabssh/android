package io.github.tabssh.ui.activities

import io.github.tabssh.sync.tombstone.TombstoneRecorder

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.cloud.CloudProviderType
import io.github.tabssh.cloud.ImportCandidate
import io.github.tabssh.cloud.newClient
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.databinding.ActivityCloudAccountsBinding
import io.github.tabssh.databinding.ItemCloudAccountBinding
import io.github.tabssh.storage.database.SystemGroupHelper
import io.github.tabssh.storage.database.entities.CloudAccount
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * @deprecated Use [io.github.tabssh.ui.fragments.CloudAccountsFragment] embedded inside the
 * main navigation instead. This standalone activity is retained for deep-link back-compat only.
 *
 * Wave 5.1 — Cloud accounts management + inventory refresh.
 *
 * Lists configured cloud accounts. Each account shows last-refresh time
 * and last droplet count. Buttons: Add (provider + name + token), Refresh
 * (fetch droplets, present import multi-select), Delete (drops account
 * record + clears stored token).
 *
 * Tokens NEVER appear in the entity. They live in
 * [SecurePasswordManager] under `cloud_token_${accountId}` so the cloud
 * account row can be dumped to logs/sync without leaking creds.
 */
@Deprecated("Use CloudAccountsFragment via main navigation instead")
class CloudAccountsActivity : TabSSHActivity() {

    private companion object {
        private const val TAG = "CloudAccounts"
        private val ROW_FORMATTER = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        fun providerIcon(tag: String) = when (tag) {
            "digitalocean" -> "🌊"
            "hetzner"      -> "🖥️"
            "linode"       -> "🟠"
            "vultr"        -> "🦅"
            "aws"          -> "🟡"
            "gcp"          -> "🔵"
            "azure"        -> "🔷"
            else           -> "☁️"
        }
    }

    private lateinit var binding: ActivityCloudAccountsBinding
    private lateinit var app: TabSSHApplication
    private lateinit var adapter: CloudAccountAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication

        binding = ActivityCloudAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBar.toolbar)

        adapter = CloudAccountAdapter(
            onRefresh   = { refreshAccount(it) },
            onDelete    = { confirmDelete(it) },
            onItemClick = { showAccountHosts(it) }
        )
        binding.recyclerAccounts.layoutManager = LinearLayoutManager(this)
        binding.recyclerAccounts.adapter = adapter

        binding.fabAdd.setOnClickListener { showAddAccountDialog() }

        // Fix: use Flow + repeatOnLifecycle to eliminate the onCreate/onResume
        // double-load race. The Flow emits whenever the table changes, so
        // refresh/delete updates the list automatically without a manual reload().
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.cloudAccountDao().observeAll().collect { accounts ->
                    adapter.submitList(accounts)
                    if (accounts.isEmpty()) {
                        binding.recyclerAccounts.visibility = View.GONE
                        binding.layoutEmptyState.visibility = View.VISIBLE
                    } else {
                        binding.layoutEmptyState.visibility = View.GONE
                        binding.recyclerAccounts.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    private inner class CloudAccountAdapter(
        private val onRefresh:   (CloudAccount) -> Unit,
        private val onDelete:    (CloudAccount) -> Unit,
        private val onItemClick: (CloudAccount) -> Unit
    ) : ListAdapter<CloudAccount, CloudAccountAdapter.ViewHolder>(AccountDiff) {

        inner class ViewHolder(val b: ItemCloudAccountBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemCloudAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val account = getItem(position)
            val b = holder.b
            val providerType = CloudProviderType.fromTag(account.provider)

            b.textProviderIcon.text = providerIcon(account.provider)
            b.textAccountName.text = account.name
            b.textProviderDetail.text = buildString {
                append(providerType?.displayName ?: account.provider)
                if (account.lastCount > 0) append(getString(R.string.cloud_accounts_host_count_suffix, account.lastCount))
            }

            b.textLastRefresh.text = if (account.lastRefreshAt > 0) {
                getString(R.string.cloud_accounts_last_sync, ROW_FORMATTER.format(Date(account.lastRefreshAt)))
            } else {
                getString(R.string.cloud_accounts_never_synced)
            }

            // Deprecated activity — read-only enabled indicator; tap row to open
            // the live CloudAccountsFragment for editing. Listener intentionally
            // null because this activity is no longer reachable from the live UI.
            b.switchEnabled.setOnCheckedChangeListener(null)
            b.switchEnabled.isChecked = account.enabled
            b.switchEnabled.isEnabled = false

            b.root.setOnClickListener      { onItemClick(account) }
            b.btnRefresh.setOnClickListener { onRefresh(account) }
            b.btnDelete.setOnClickListener  { onDelete(account) }
        }
    }

    private object AccountDiff : DiffUtil.ItemCallback<CloudAccount>() {
        override fun areItemsTheSame(old: CloudAccount, new: CloudAccount) = old.id == new.id
        override fun areContentsTheSame(old: CloudAccount, new: CloudAccount) = old == new
    }

    // ── Add account dialog ───────────────────────────────────────────────────

    private fun showAddAccountDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_cloud_account, null, false)

        val tilName     = dialogView.findViewById<TextInputLayout>(R.id.til_account_name)
        val editName    = dialogView.findViewById<TextInputEditText>(R.id.edit_account_name)
        val tilProvider = dialogView.findViewById<TextInputLayout>(R.id.til_provider)
        val spinProvider= dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_provider)
        val tilToken    = dialogView.findViewById<TextInputLayout>(R.id.til_api_token)
        val editToken   = dialogView.findViewById<TextInputEditText>(R.id.edit_api_token)
        val tvTokenHelp = dialogView.findViewById<TextView>(R.id.tv_token_help)

        val providerLabels = CloudProviderType.entries.map { it.displayName }
        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, providerLabels)
        spinProvider.setAdapter(providerAdapter)
        spinProvider.setText(providerLabels.first(), false)
        tvTokenHelp.text = CloudProviderType.entries.first().tokenHelp

        spinProvider.setOnItemClickListener { _, _, position, _ ->
            val pt = CloudProviderType.entries[position]
            tvTokenHelp.text = pt.tokenHelp
            tilToken.helperText = pt.tokenHelp
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cloud_add_account_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = editName.text?.toString()?.trim().orEmpty()
                val token = editToken.text?.toString().orEmpty()
                val selectedLabel = spinProvider.text?.toString().orEmpty()
                val providerType = CloudProviderType.entries
                    .firstOrNull { it.displayName == selectedLabel }
                    ?: CloudProviderType.entries.first()

                if (name.isBlank()) {
                    tilName.error = getString(R.string.cloud_error_name_required)
                    return@setPositiveButton
                }
                if (token.isBlank()) {
                    tilToken.error = getString(R.string.cloud_error_token_required)
                    return@setPositiveButton
                }
                saveAccount(name, providerType, token)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── Business logic (unchanged from original) ─────────────────────────────

    private fun saveAccount(name: String, provider: CloudProviderType, token: String) {
        lifecycleScope.launch {
            val account = CloudAccount(name = name, provider = provider.tag)
            try {
                withContext(Dispatchers.IO) {
                    app.database.cloudAccountDao().upsert(account)
                    app.securePasswordManager.storePassword(
                        "cloud_token_${account.id}", token,
                        SecurePasswordManager.StorageLevel.ENCRYPTED
                    )
                }
                Toast.makeText(this@CloudAccountsActivity, getString(R.string.cloud_accounts_toast_saved, account.name), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e(TAG, "Save cloud account failed", e)
                Toast.makeText(this@CloudAccountsActivity, getString(R.string.cloud_accounts_toast_save_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshAccount(account: CloudAccount) {
        Toast.makeText(this, getString(R.string.cloud_accounts_toast_refreshing, account.name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                app.securePasswordManager.retrievePassword("cloud_token_${account.id}")
            }
            if (token.isNullOrBlank()) {
                runOnUiThread {
                    Toast.makeText(this@CloudAccountsActivity, getString(R.string.cloud_manager_toast_token_missing), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val providerType = CloudProviderType.fromTag(account.provider)
            if (providerType == null) {
                runOnUiThread {
                    Toast.makeText(this@CloudAccountsActivity, getString(R.string.cloud_manager_toast_unknown_provider, account.provider), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val provider = providerType.newClient()
            val candidates = try {
                withContext(Dispatchers.IO) { provider.fetchInventory(token, account.name) }
            } catch (e: Exception) {
                Logger.e(TAG, "Inventory fetch failed", e)
                runOnUiThread {
                    Toast.makeText(this@CloudAccountsActivity, getString(R.string.cloud_accounts_toast_refresh_failed, account.name, e.message), Toast.LENGTH_LONG).show()
                }
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
            runOnUiThread { showImportPicker(account, candidates) }
        }
    }

    private fun showImportPicker(account: CloudAccount, candidates: List<ImportCandidate>) {
        if (candidates.isEmpty()) {
            Toast.makeText(this, getString(R.string.cloud_accounts_toast_zero_hosts_found, account.name), Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { "${it.profile.getDisplayName()} (${it.sourceLabel})" }.toTypedArray()
        val checked = BooleanArray(candidates.size) { true }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cloud_accounts_import_picker_title, account.name, candidates.size))
            .setMultiChoiceItems(labels, checked) { _, idx, isChecked -> checked[idx] = isChecked }
            .setPositiveButton(getString(R.string.conn_edit_import)) { _, _ ->
                val picked = candidates.filterIndexed { i, _ -> checked[i] }
                importPicked(picked)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Wave 6.5 — Dedup on import. For each candidate, if an existing
     * connection has the same (host, port, username) AND the same
     * `cloud_source` tag in advancedSettings, update it in place (name +
     * region timestamp) instead of inserting a duplicate. Anything else is
     * inserted fresh. Returns inserted/updated counts so the user knows
     * what happened.
     */
    private fun importPicked(picked: List<ImportCandidate>) {
        if (picked.isEmpty()) return
        lifecycleScope.launch {
            try {
                val (inserted, updated) = withContext(Dispatchers.IO) {
                val existing = app.database.connectionDao().getAllConnectionsList()
                var ins = 0
                var upd = 0
                val cloudGroupId = SystemGroupHelper.getOrCreateSystemGroupId(
                    app.database, "cloud", "Cloud Instances", "cloud"
                )
                for (cand in picked) {
                    val src = extractCloudSource(cand.profile.advancedSettings)
                    val match = existing.firstOrNull { e ->
                        e.host == cand.profile.host &&
                        e.port == cand.profile.port &&
                        e.username == cand.profile.username &&
                        extractCloudSource(e.advancedSettings) == src
                    }
                    if (match != null) {
                        app.database.connectionDao().updateConnection(
                            match.copy(
                                name = cand.profile.name,
                                advancedSettings = cand.profile.advancedSettings,
                                groupId = cloudGroupId,
                                modifiedAt = System.currentTimeMillis()
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
                runOnUiThread {
                    Toast.makeText(
                        this@CloudAccountsActivity,
                        getString(R.string.cloud_accounts_toast_inserted_updated, inserted, updated),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Bulk insert/update failed", e)
                runOnUiThread {
                    Toast.makeText(this@CloudAccountsActivity, getString(R.string.cloud_accounts_toast_import_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Pull the `cloud_source` field out of the advancedSettings JSON, or null. */
    private fun extractCloudSource(advanced: String?): String? {
        if (advanced.isNullOrBlank()) return null
        return try {
            org.json.JSONObject(advanced).optString("cloud_source").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    /**
     * Shows the SSH connections already imported from [account], identified by
     * the `cloud_source` field in their advancedSettings JSON. Each row shows
     * the connection name and host. Tapping a row opens its terminal; long-
     * pressing is not needed here because this is a read-only quick-view.
     */
    private fun showAccountHosts(account: CloudAccount) {
        lifecycleScope.launch {
            val allConnections = withContext(Dispatchers.IO) {
                app.database.connectionDao().getAllConnectionsList()
            }
            // cloud_source is written as "{provider}:{accountName}" by every client
            val accountSource = "${account.provider}:${account.name}"
            val hosts = allConnections.filter { conn ->
                extractCloudSource(conn.advancedSettings) == accountSource
            }
            runOnUiThread {
                if (hosts.isEmpty()) {
                    MaterialAlertDialogBuilder(this@CloudAccountsActivity)
                        .setTitle(account.name)
                        .setMessage(getString(R.string.cloud_accounts_no_hosts_imported))
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                    return@runOnUiThread
                }
                val labels = hosts.map { "${it.getDisplayName()}  –  ${it.host}:${it.port}" }.toTypedArray()
                MaterialAlertDialogBuilder(this@CloudAccountsActivity)
                    .setTitle(
                        if (hosts.size == 1) getString(R.string.cloud_accounts_hosts_title_singular, account.name, hosts.size)
                        else getString(R.string.cloud_accounts_hosts_title_plural, account.name, hosts.size)
                    )
                    .setItems(labels) { _, idx ->
                        val profile = hosts[idx]
                        startActivity(
                            TabTerminalActivity.createIntent(
                                this@CloudAccountsActivity, profile, autoConnect = true
                            )
                        )
                    }
                    .setNegativeButton(getString(R.string.close), null)
                    .show()
            }
        }
    }

    private fun confirmDelete(account: CloudAccount) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cloud_accounts_delete_title, account.name))
            .setMessage(getString(R.string.cloud_accounts_delete_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.cloudAccountDao().delete(account)
                            // H6 — record the deletion so it propagates and is not resurrected.
                            TombstoneRecorder.record(app, TombstoneRecorder.CLOUD_ACCOUNT, account.id)
                            app.securePasswordManager.clearPassword("cloud_token_${account.id}")
                            // Keep the Panes registry and any saved pane-group membership accurate.
                            io.github.tabssh.storage.registry.ConnectableHostRegistry
                                .removeCloudAccount(app.database, account.id)
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Delete failed", e)
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

}
