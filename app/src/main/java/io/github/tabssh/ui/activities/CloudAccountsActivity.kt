package io.github.tabssh.ui.activities

import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
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
import io.github.tabssh.cloud.VultrClient
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.storage.database.entities.CloudAccount
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
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
class CloudAccountsActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "CloudAccounts"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val ROW_FORMATTER = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }

    private lateinit var app: TabSSHApplication
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        val toolbar = Toolbar(this).apply {
            title = "Cloud Accounts"
            setBackgroundResource(R.color.primary_500)
            setTitleTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(toolbar)

        val addBtn = Button(this).apply { text = "Add cloud account…" }
        addBtn.setOnClickListener { showAddAccountDialog() }
        root.addView(addBtn)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8))
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val accounts = try {
                app.database.cloudAccountDao().getAll()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load cloud accounts", e)
                emptyList()
            }
            runOnUiThread {
                listContainer.removeAllViews()
                if (accounts.isEmpty()) {
                    val empty = TextView(this@CloudAccountsActivity).apply {
                        text = "No cloud accounts. Tap “Add cloud account…” to import droplets / instances."
                        setPadding(dp(16))
                    }
                    listContainer.addView(empty)
                } else {
                    accounts.forEach { listContainer.addView(buildAccountCard(it)) }
                }
            }
        }
    }

    private fun buildAccountCard(account: CloudAccount): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12))
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            setBackgroundColor(0xFF1A1A1A.toInt())
        }
        card.addView(TextView(this).apply {
            text = "${account.name} · ${CloudProviderType.fromTag(account.provider)?.displayName ?: account.provider}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        })
        card.addView(TextView(this).apply {
            val ts = if (account.lastRefreshAt > 0) ROW_FORMATTER.format(Date(account.lastRefreshAt)) else "never"
            text = "Last refresh: $ts · ${account.lastCount} hosts"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 12f
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val refresh = Button(this).apply { text = "Refresh" }
        val delete = Button(this).apply { text = "Delete" }
        refresh.setOnClickListener { refreshAccount(account) }
        delete.setOnClickListener { confirmDelete(account) }
        row.addView(refresh); row.addView(delete)
        card.addView(row)
        return card
    }

    private fun showAddAccountDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        val nameEdit = EditText(this).apply { hint = "Account name (e.g. work-do)" }
        val providerSpinner = Spinner(this)
        val providerLabels = CloudProviderType.entries.map { it.displayName }.toTypedArray()
        providerSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, providerLabels
        )
        val tokenHelp = TextView(this)
        val tokenEdit = EditText(this).apply {
            hint = "API token / Bearer secret"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // Wave 8.1 — provider-specific token format hint (AWS uses AKID:SECRET:REGION).
        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                val pt = CloudProviderType.entries[position]
                tokenHelp.text = pt.tokenHelp
                tokenEdit.hint = pt.tokenHelp
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        container.addView(TextView(this).apply { text = "Name" })
        container.addView(nameEdit)
        container.addView(TextView(this).apply { text = "Provider" })
        container.addView(providerSpinner)
        container.addView(TextView(this).apply { text = "Token" })
        container.addView(tokenHelp)
        container.addView(tokenEdit)

        AlertDialog.Builder(this)
            .setTitle("Add cloud account")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = nameEdit.text.toString().trim()
                val token = tokenEdit.text.toString()
                val providerType = CloudProviderType.entries[providerSpinner.selectedItemPosition]
                if (name.isBlank() || token.isBlank()) {
                    Toast.makeText(this, "Name + token required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveAccount(name, providerType, token)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAccount(name: String, provider: CloudProviderType, token: String) {
        lifecycleScope.launch {
            val account = CloudAccount(name = name, provider = provider.tag)
            try {
                app.database.cloudAccountDao().upsert(account)
                app.securePasswordManager.storePassword(
                    "cloud_token_${account.id}", token,
                    SecurePasswordManager.StorageLevel.ENCRYPTED
                )
                Toast.makeText(this@CloudAccountsActivity, "Saved '${account.name}'", Toast.LENGTH_SHORT).show()
                reload()
            } catch (e: Exception) {
                Logger.e(TAG, "Save cloud account failed", e)
                Toast.makeText(this@CloudAccountsActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshAccount(account: CloudAccount) {
        Toast.makeText(this, "Refreshing ${account.name}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val token = app.securePasswordManager.retrievePassword("cloud_token_${account.id}")
            if (token.isNullOrBlank()) {
                runOnUiThread {
                    Toast.makeText(this@CloudAccountsActivity, "Token missing — re-add account", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val provider: CloudProvider = when (CloudProviderType.fromTag(account.provider)) {
                CloudProviderType.DIGITALOCEAN -> DigitalOceanClient()
                CloudProviderType.HETZNER -> HetznerClient()
                CloudProviderType.LINODE -> LinodeClient()
                CloudProviderType.VULTR -> VultrClient()
                CloudProviderType.AWS -> AwsEc2Client()
                CloudProviderType.GCP -> GcpComputeClient()
                CloudProviderType.AZURE -> AzureVmClient()
                null -> {
                    runOnUiThread {
                        Toast.makeText(this@CloudAccountsActivity, "Unknown provider: ${account.provider}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
            }
            val candidates = try {
                provider.fetchInventory(token, account.name)
            } catch (e: Exception) {
                Logger.e(TAG, "Inventory fetch failed", e)
                runOnUiThread {
                    Toast.makeText(this@CloudAccountsActivity, "Refresh failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            try {
                app.database.cloudAccountDao().update(
                    account.copy(
                        lastRefreshAt = System.currentTimeMillis(),
                        lastCount = candidates.size
                    )
                )
            } catch (_: Exception) {}
            runOnUiThread { showImportPicker(account, candidates) }
        }
    }

    private fun showImportPicker(account: CloudAccount, candidates: List<ImportCandidate>) {
        if (candidates.isEmpty()) {
            Toast.makeText(this, "${account.name}: 0 hosts found", Toast.LENGTH_SHORT).show()
            reload()
            return
        }
        val labels = candidates.map { "${it.profile.getDisplayName()} (${it.sourceLabel})" }.toTypedArray()
        val checked = BooleanArray(candidates.size) { true }
        AlertDialog.Builder(this)
            .setTitle("${account.name}: pick to import (${candidates.size})")
            .setMultiChoiceItems(labels, checked) { _, idx, isChecked -> checked[idx] = isChecked }
            .setPositiveButton("Import") { _, _ ->
                val picked = candidates.filterIndexed { i, _ -> checked[i] }
                importPicked(picked)
            }
            .setNegativeButton("Cancel", null)
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
                val existing = app.database.connectionDao().getAllConnectionsList()
                var inserted = 0
                var updated = 0
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
                                modifiedAt = System.currentTimeMillis()
                            )
                        )
                        updated++
                    } else {
                        app.database.connectionDao().insertConnection(cand.profile)
                        inserted++
                    }
                }
                runOnUiThread {
                    Toast.makeText(
                        this@CloudAccountsActivity,
                        "Inserted $inserted, updated $updated",
                        Toast.LENGTH_SHORT
                    ).show()
                    reload()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Bulk insert/update failed", e)
                runOnUiThread {
                    Toast.makeText(this@CloudAccountsActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun confirmDelete(account: CloudAccount) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${account.name}?")
            .setMessage("Removes this cloud account record and its stored token. Imported connections stay.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.database.cloudAccountDao().delete(account)
                        app.securePasswordManager.clearPassword("cloud_token_${account.id}")
                    } catch (e: Exception) {
                        Logger.e(TAG, "Delete failed", e)
                    }
                    reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
