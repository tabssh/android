package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.cloud.CloudInstanceState
import io.github.tabssh.cloud.CloudProviderType
import io.github.tabssh.cloud.newClient
import io.github.tabssh.databinding.ActivityCloudManagerBinding
import io.github.tabssh.storage.database.entities.CloudAccount
import io.github.tabssh.ui.adapters.CloudInstanceAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-account instance manager for Cloud Accounts.
 *
 * Launched from CloudAccountsFragment when the user taps a cloud account row.
 * Shows live instance state (running / stopped / unknown) and provides a
 * single power-toggle button per instance (Start when stopped, Stop when
 * running) plus a Connect shortcut for running instances with an IP.
 *
 * The activity fetches fresh live state on resume and after every power action.
 */
class CloudAccountManagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CloudAccountManager"
        private const val EXTRA_ACCOUNT_ID = "extra_account_id"

        fun createIntent(context: Context, account: CloudAccount): Intent =
            Intent(context, CloudAccountManagerActivity::class.java)
                .putExtra(EXTRA_ACCOUNT_ID, account.id)
    }

    private lateinit var binding: ActivityCloudManagerBinding
    private lateinit var app: TabSSHApplication
    private lateinit var adapter: CloudInstanceAdapter
    private var account: CloudAccount? = null
    private var cachedInstances: List<CloudInstanceState> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication

        binding = ActivityCloudManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = CloudInstanceAdapter(
            onPowerToggle  = { inst -> handlePowerToggle(inst) },
            onConnect      = { inst -> handleConnect(inst) },
            onRestart      = { inst -> handleRestart(inst, force = false) },
            onForceRestart = { inst -> handleRestart(inst, force = true) }
        )
        binding.recyclerInstances.layoutManager = LinearLayoutManager(this)
        binding.recyclerInstances.adapter = adapter

        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        if (accountId.isNullOrBlank()) {
            Toast.makeText(this, "No account specified", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                app.database.cloudAccountDao().getById(accountId)
            }
            if (loaded == null) {
                Toast.makeText(this@CloudAccountManagerActivity, "Account not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            account = loaded
            binding.toolbar.title = loaded.name
            loadInstances(loaded)
        }
    }

    override fun onResume() {
        super.onResume()
        account?.let { loadInstances(it) }
    }

    private fun loadInstances(acct: CloudAccount) {
        binding.progressLoading.visibility = View.VISIBLE
        binding.recyclerInstances.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.GONE

        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                app.securePasswordManager.retrievePassword("cloud_token_${acct.id}")
            }
            if (token.isNullOrBlank()) {
                binding.progressLoading.visibility = View.GONE
                Toast.makeText(this@CloudAccountManagerActivity,
                    "Token missing — re-add account", Toast.LENGTH_LONG).show()
                return@launch
            }

            val providerType = CloudProviderType.fromTag(acct.provider)
            if (providerType == null) {
                binding.progressLoading.visibility = View.GONE
                Toast.makeText(this@CloudAccountManagerActivity,
                    "Unknown provider: ${acct.provider}", Toast.LENGTH_LONG).show()
                return@launch
            }

            val instances = try {
                withContext(Dispatchers.IO) {
                    providerType.newClient().fetchLiveInstances(token)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "fetchLiveInstances failed for ${acct.name}", e)
                binding.progressLoading.visibility = View.GONE
                Toast.makeText(this@CloudAccountManagerActivity,
                    "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            cachedInstances = instances
            binding.progressLoading.visibility = View.GONE
            if (instances.isEmpty()) {
                binding.layoutEmptyState.visibility = View.VISIBLE
            } else {
                binding.recyclerInstances.visibility = View.VISIBLE
                adapter.submitList(instances)
            }
        }
    }

    private fun handlePowerToggle(inst: CloudInstanceState) {
        val acct = account ?: return
        val isRunning = inst.status == "running"
        val actionLabel = if (isRunning) "Stop" else "Start"

        AlertDialog.Builder(this)
            .setTitle("$actionLabel ${inst.name}?")
            .setMessage(if (isRunning) "Stop this instance?" else "Start this instance?")
            .setPositiveButton(actionLabel) { _, _ -> performPowerAction(acct, inst, isRunning) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performPowerAction(acct: CloudAccount, inst: CloudInstanceState, isRunning: Boolean) {
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                app.securePasswordManager.retrievePassword("cloud_token_${acct.id}")
            }
            if (token.isNullOrBlank()) {
                Toast.makeText(this@CloudAccountManagerActivity,
                    "Token missing — re-add account", Toast.LENGTH_LONG).show()
                return@launch
            }

            val providerType = CloudProviderType.fromTag(acct.provider) ?: return@launch
            val client = providerType.newClient()

            val success = try {
                withContext(Dispatchers.IO) {
                    if (isRunning) client.stopInstance(token, inst.id)
                    else client.startInstance(token, inst.id)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Power action failed for ${inst.name}", e)
                false
            }

            val verb = if (isRunning) "stop" else "start"
            if (success) {
                Toast.makeText(this@CloudAccountManagerActivity,
                    "${inst.name}: ${verb} request sent", Toast.LENGTH_SHORT).show()
                loadInstances(acct)
            } else {
                Toast.makeText(this@CloudAccountManagerActivity,
                    "Failed to $verb ${inst.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleRestart(inst: CloudInstanceState, force: Boolean) {
        val acct = account ?: return
        val label = if (force) "Force Restart" else "Restart"
        val msg = if (force) "Hard power-cycle ${inst.name}?" else "Gracefully reboot ${inst.name}?"
        AlertDialog.Builder(this)
            .setTitle("$label ${inst.name}?")
            .setMessage(msg)
            .setPositiveButton(label) { _, _ -> performRestartAction(acct, inst, force) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performRestartAction(acct: CloudAccount, inst: CloudInstanceState, force: Boolean) {
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                app.securePasswordManager.retrievePassword("cloud_token_${acct.id}")
            }
            if (token.isNullOrBlank()) {
                Toast.makeText(this@CloudAccountManagerActivity,
                    "Token missing — re-add account", Toast.LENGTH_LONG).show()
                return@launch
            }
            val providerType = CloudProviderType.fromTag(acct.provider) ?: return@launch
            val client = providerType.newClient()
            val success = try {
                withContext(Dispatchers.IO) {
                    if (force) client.forceRestartInstance(token, inst.id)
                    else client.restartInstance(token, inst.id)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Restart failed for ${inst.name}", e)
                false
            }
            val verb = if (force) "force restart" else "restart"
            if (success) {
                Toast.makeText(this@CloudAccountManagerActivity,
                    "${inst.name}: $verb request sent", Toast.LENGTH_SHORT).show()
                loadInstances(acct)
            } else {
                Toast.makeText(this@CloudAccountManagerActivity,
                    "Failed to $verb ${inst.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleConnect(inst: CloudInstanceState) {
        val ip = inst.ip
        if (ip.isNullOrBlank()) {
            Toast.makeText(this, "No public IP available", Toast.LENGTH_SHORT).show()
            return
        }

        // Look for a matching imported connection profile; fall back to creating a temp one
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                app.database.connectionDao().getAllConnectionsList().firstOrNull { conn ->
                    conn.host == ip && conn.port == 22
                }
            }
            if (existing != null) {
                startActivity(
                    TabTerminalActivity.createIntent(this@CloudAccountManagerActivity, existing, autoConnect = true)
                )
            } else {
                // No imported connection — prompt the user to import via the account refresh first
                AlertDialog.Builder(this@CloudAccountManagerActivity)
                    .setTitle("Connect to ${inst.name}")
                    .setMessage("No imported connection found for $ip. Use the cloud account refresh to import this host first.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}
