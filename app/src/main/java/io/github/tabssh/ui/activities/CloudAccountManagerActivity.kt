package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.cloud.CloudAuthException
import io.github.tabssh.cloud.CloudInstanceState
import io.github.tabssh.cloud.CloudProvider
import io.github.tabssh.cloud.CloudProviderType
import io.github.tabssh.cloud.OciCloudClient
import io.github.tabssh.cloud.newClient
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.databinding.ActivityCloudManagerBinding
import io.github.tabssh.storage.database.entities.CloudAccount
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.ui.adapters.CloudInstanceAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import io.github.tabssh.utils.tabSSHApp

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
class CloudAccountManagerActivity : TabSSHActivity() {

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

    /**
     * Single CloudProvider client instance reused across loadInstances and
     * power/restart actions for this activity's lifetime. GCP and Azure cache
     * zone / resource-group lookups on the client between fetchLiveInstances
     * and *Instance() calls; creating a fresh client per action would empty
     * the cache and silently fail those providers. The instance is keyed by
     * provider tag so a token-edit / provider-switch via Edit resets it.
     */
    private var providerClient: CloudProvider? = null
    private var providerClientTag: String? = null

    private fun providerClientFor(tag: String): CloudProvider? {
        val type = CloudProviderType.fromTag(tag) ?: return null
        val current = providerClient
        if (current != null && providerClientTag == tag) return current
        val fresh = type.newClient()
        providerClient = fresh
        providerClientTag = tag
        return fresh
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = tabSSHApp

        binding = ActivityCloudManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBar.toolbar)

        adapter = CloudInstanceAdapter(
            onPowerToggle     = { inst -> handlePowerToggle(inst) },
            onConnect         = { inst -> handleConnect(inst) },
            onRestart         = { inst -> handleRestart(inst, force = false) },
            onForceRestart    = { inst -> handleRestart(inst, force = true) },
            onEditCredentials = { inst -> showEditCredentialsDialog(inst) }
        )
        binding.recyclerInstances.layoutManager = LinearLayoutManager(this)
        binding.recyclerInstances.adapter = adapter

        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        if (accountId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.cloud_manager_toast_no_account_specified), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                app.database.cloudAccountDao().getById(accountId)
            }
            if (loaded == null) {
                Toast.makeText(this@CloudAccountManagerActivity, getString(R.string.cloud_manager_toast_account_not_found), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            account = loaded
            binding.appBar.toolbar.title = loaded.name
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
                    getString(R.string.cloud_manager_toast_token_missing), Toast.LENGTH_LONG).show()
                return@launch
            }

            val client = providerClientFor(acct.provider)
            if (client == null) {
                binding.progressLoading.visibility = View.GONE
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_unknown_provider, acct.provider), Toast.LENGTH_LONG).show()
                return@launch
            }

            val instances = try {
                withContext(Dispatchers.IO) {
                    client.fetchLiveInstances(token)
                }
            } catch (e: CloudAuthException) {
                Logger.e(TAG, "fetchLiveInstances auth failed for ${acct.name}", e)
                binding.progressLoading.visibility = View.GONE
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_token_invalid_detail, e.message), Toast.LENGTH_LONG).show()
                return@launch
            } catch (e: Exception) {
                Logger.e(TAG, "fetchLiveInstances failed for ${acct.name}", e)
                binding.progressLoading.visibility = View.GONE
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_load_failed, e.message), Toast.LENGTH_LONG).show()
                return@launch
            }

            persistOciCloudPin(acct, token, client)
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
        val actionLabel = if (isRunning) getString(R.string.cloud_manager_action_stop) else getString(R.string.cloud_manager_action_start)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cloud_manager_power_dialog_title, actionLabel, inst.name))
            .setMessage(if (isRunning) getString(R.string.cloud_manager_power_dialog_message_stop) else getString(R.string.cloud_manager_power_dialog_message_start))
            .setPositiveButton(actionLabel) { _, _ -> performPowerAction(acct, inst, isRunning) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun performPowerAction(acct: CloudAccount, inst: CloudInstanceState, isRunning: Boolean) {
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                app.securePasswordManager.retrievePassword("cloud_token_${acct.id}")
            }
            if (token.isNullOrBlank()) {
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_token_missing), Toast.LENGTH_LONG).show()
                return@launch
            }

            val client = providerClientFor(acct.provider) ?: return@launch

            val success = try {
                withContext(Dispatchers.IO) {
                    if (isRunning) client.stopInstance(token, inst.id)
                    else client.startInstance(token, inst.id)
                }
            } catch (e: CloudAuthException) {
                Logger.e(TAG, "Power action auth failed for ${inst.name}", e)
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_token_invalid), Toast.LENGTH_LONG).show()
                return@launch
            } catch (e: Exception) {
                Logger.e(TAG, "Power action failed for ${inst.name}", e)
                false
            }

            persistOciCloudPin(acct, token, client)
            val verb = if (isRunning) getString(R.string.cloud_manager_verb_stop) else getString(R.string.cloud_manager_verb_start)
            if (success) {
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_request_sent, inst.name, verb), Toast.LENGTH_SHORT).show()
                loadInstances(acct)
            } else {
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_action_failed, verb, inst.name), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleRestart(inst: CloudInstanceState, force: Boolean) {
        val acct = account ?: return
        val label = if (force) getString(R.string.cloud_manager_action_force_restart) else getString(R.string.cloud_manager_action_restart)
        val msg = if (force) getString(R.string.cloud_manager_restart_dialog_message_force, inst.name) else getString(R.string.cloud_manager_restart_dialog_message_graceful, inst.name)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cloud_manager_power_dialog_title, label, inst.name))
            .setMessage(msg)
            .setPositiveButton(label) { _, _ -> performRestartAction(acct, inst, force) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun performRestartAction(acct: CloudAccount, inst: CloudInstanceState, force: Boolean) {
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                app.securePasswordManager.retrievePassword("cloud_token_${acct.id}")
            }
            if (token.isNullOrBlank()) {
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_token_missing), Toast.LENGTH_LONG).show()
                return@launch
            }
            val client = providerClientFor(acct.provider) ?: return@launch
            val success = try {
                withContext(Dispatchers.IO) {
                    if (force) client.forceRestartInstance(token, inst.id)
                    else client.restartInstance(token, inst.id)
                }
            } catch (e: CloudAuthException) {
                Logger.e(TAG, "Restart auth failed for ${inst.name}", e)
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_token_invalid), Toast.LENGTH_LONG).show()
                return@launch
            } catch (e: Exception) {
                Logger.e(TAG, "Restart failed for ${inst.name}", e)
                false
            }
            persistOciCloudPin(acct, token, client)
            val verb = if (force) getString(R.string.cloud_manager_verb_force_restart) else getString(R.string.cloud_manager_verb_restart)
            if (success) {
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_request_sent, inst.name, verb), Toast.LENGTH_SHORT).show()
                loadInstances(acct)
            } else {
                Toast.makeText(this@CloudAccountManagerActivity,
                    getString(R.string.cloud_manager_toast_action_failed, verb, inst.name), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleConnect(inst: CloudInstanceState) {
        val ip = inst.ip
        if (ip.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.cloud_manager_toast_no_public_ip), Toast.LENGTH_SHORT).show()
            return
        }
        val acct = account ?: return
        lifecycleScope.launch {
            // Load stored per-instance SSH credentials (username / password / identityId / port).
            // These are stored scoped to the cloud account and never written to the connections DB.
            val credsJson = withContext(Dispatchers.IO) {
                app.securePasswordManager.retrievePassword(hostCredKey(acct.id, inst.id))
            }
            val creds = credsJson?.let {
                runCatching { JSONObject(it) }
                    .onFailure { e -> Logger.w("CloudAccountManager", "Stored instance credentials JSON is corrupt — falling back to defaults", e) }
                    .getOrNull()
            }
            val username   = creds?.optString("username").takeIf { !it.isNullOrBlank() } ?: "root"
            val password   = creds?.optString("password").takeIf { !it.isNullOrBlank() }
            val identityId = creds?.optString("identityId").takeIf { !it.isNullOrBlank() }
            val port       = creds?.optInt("port", 22)?.takeIf { it in 1..65535 } ?: 22

            // Build an in-memory ConnectionProfile — never persisted to Room.
            // The host field is the cloud instance's public IP; name is shown
            // in the tab bar until the remote sets an OSC 0/2 title.
            val tempProfile = ConnectionProfile(
                id         = inst.id,
                name       = inst.name,
                host       = ip,
                port       = port,
                username   = username,
                identityId = identityId
            )

            // If a password was stored, put it in SecurePasswordManager under the
            // profile ID so SSHConnection can pick it up the same way it does for
            // regular connection profiles.
            if (password != null) {
                withContext(Dispatchers.IO) {
                    app.securePasswordManager.storePassword(
                        inst.id, password, SecurePasswordManager.StorageLevel.SESSION_ONLY
                    )
                }
            }

            // Tracked at tap-Connect, not a confirmed SSH round trip — cloud
            // connects are a fire-and-forget Intent launch with no success
            // callback available. Local-only usage stat, never synced.
            withContext(Dispatchers.IO) {
                app.database.cloudAccountDao().updateLastConnected(acct.id)
            }

            startActivity(
                TabTerminalActivity.createIntent(this@CloudAccountManagerActivity, tempProfile, autoConnect = true)
            )
        }
    }

    /**
     * Reads the TLS cert pin captured by [OciCloudClient] during the most recent
     * API call and writes it back to the Keystore token JSON if it differs from
     * the value already stored there.  No-ops silently for non-OCI providers.
     */
    private suspend fun persistOciCloudPin(
        acct: CloudAccount,
        currentToken: String,
        client: io.github.tabssh.cloud.CloudProvider
    ) {
        val ociClient = client as? OciCloudClient ?: return
        val captured = ociClient.getCapturedCertSha256() ?: return
        val existing = try {
            JSONObject(currentToken).optString("tls_pin").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        if (captured == existing) return
        try {
            val updatedJson = JSONObject(currentToken).put("tls_pin", captured).toString()
            withContext(Dispatchers.IO) {
                app.securePasswordManager.storePassword(
                    "cloud_token_${acct.id}",
                    updatedJson,
                    SecurePasswordManager.StorageLevel.ENCRYPTED
                )
            }
            Logger.i(TAG, "OCI cloud account '${acct.name}' TLS pin updated: $captured")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to persist OCI cloud pin for ${acct.name}", e)
        }
    }

    /**
     * SharedPreferences key for per-instance SSH credentials.
     * Scoped to the cloud account so two accounts with the same instance ID
     * (unlikely but possible across providers) don't collide.
     */
    private fun hostCredKey(accountId: String, instanceId: String) =
        "cloud_host_creds_${accountId}_${instanceId}"

    /**
     * Edit dialog for SSH credentials associated with a specific cloud instance.
     * Loads any previously stored creds to pre-fill the form. On save, persists
     * to SecurePasswordManager — never to the connections DB.
     *
     * Long-press on any instance row in the list opens this dialog.
     */
    private fun showEditCredentialsDialog(inst: CloudInstanceState) {
        val acct = account ?: return
        lifecycleScope.launch {
            // Load existing creds and identities in parallel (both IO).
            val (credsJson, identities) = withContext(Dispatchers.IO) {
                val c = app.securePasswordManager.retrievePassword(hostCredKey(acct.id, inst.id))
                val ids = app.database.identityDao().getAllIdentitiesList()
                c to ids
            }
            val existing = credsJson?.let {
                runCatching { JSONObject(it) }
                    .onFailure { e -> Logger.w("CloudAccountManager", "Stored instance credentials JSON is corrupt — showing empty form", e) }
                    .getOrNull()
            }

            val dp  = resources.displayMetrics.density
            val ctx = this@CloudAccountManagerActivity

            val scroll = android.widget.ScrollView(ctx)
            val ll = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val pad = (16 * dp).toInt()
                setPadding(pad, pad, pad, pad)
            }
            scroll.addView(ll)

            fun field(hint: String, initial: String = "", password: Boolean = false): TextInputEditText {
                val til = TextInputLayout(ctx, null,
                    com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                    this.hint = hint
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = (8 * dp).toInt() }
                    if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
                val edit = TextInputEditText(til.context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setSingleLine(true)
                    inputType = if (password)
                        android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else
                        android.text.InputType.TYPE_CLASS_TEXT
                    setText(initial)
                }
                til.addView(edit)
                ll.addView(til)
                return edit
            }

            val editUsername = field(getString(R.string.cloud_manager_hint_ssh_username), existing?.optString("username") ?: "root")
            val editPassword = field(getString(R.string.cloud_manager_hint_ssh_password), existing?.optString("password") ?: "", password = true)
            val editPort     = field(getString(R.string.route_port_hint), existing?.optInt("port", 22)?.toString() ?: "22")

            // Identity dropdown — "(none)" + all stored identities.
            val identityTil = TextInputLayout(ctx, null,
                com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle).apply {
                hint = getString(R.string.cloud_manager_hint_ssh_identity)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * dp).toInt() }
            }
            val identityDropdown = android.widget.AutoCompleteTextView(identityTil.context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            identityTil.addView(identityDropdown)
            ll.addView(identityTil)

            val noneLabel = getString(R.string.container_detail_none)
            val identityLabels = listOf(noneLabel) + identities.map { it.getDisplayName() }
            identityDropdown.setAdapter(
                ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, identityLabels)
            )
            // Pre-select the identity that was previously saved, if any.
            val preselectedIdentity = existing?.optString("identityId")
                ?.let { savedId -> identities.firstOrNull { it.id == savedId } }
            identityDropdown.setText(preselectedIdentity?.getDisplayName() ?: noneLabel, false)

            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.cloud_manager_credentials_dialog_title, inst.name))
                .setView(scroll)
                .setPositiveButton(getString(R.string.save)) { _, _ -> /* overridden */ }
                .setNegativeButton(getString(R.string.cancel), null)
                .also { b ->
                    // Only show Clear if credentials have previously been saved.
                    if (credsJson != null) {
                        b.setNeutralButton(getString(R.string.action_clear)) { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                app.securePasswordManager.clearPassword(hostCredKey(acct.id, inst.id))
                            }
                            Toast.makeText(ctx, getString(R.string.cloud_manager_toast_credentials_cleared, inst.name), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .create()
            dialog.show()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = editUsername.text?.toString()?.trim().orEmpty().ifBlank { "root" }
                val password = editPassword.text?.toString().orEmpty()
                val port     = editPort.text?.toString()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
                val selectedLabel = identityDropdown.text?.toString().orEmpty()
                val selectedIdentity: Identity? = if (selectedLabel == noneLabel) null
                    else identities.firstOrNull { it.getDisplayName() == selectedLabel }

                val json = JSONObject()
                    .put("username",   username)
                    .put("password",   password)
                    .put("port",       port)
                    .put("identityId", selectedIdentity?.id ?: "")
                    .toString()

                lifecycleScope.launch {
                    val stored = withContext(Dispatchers.IO) {
                        app.securePasswordManager.storePassword(
                            hostCredKey(acct.id, inst.id), json,
                            SecurePasswordManager.StorageLevel.ENCRYPTED
                        )
                    }
                    if (stored) {
                        val identityLabel = selectedIdentity?.getDisplayName() ?: getString(R.string.cloud_manager_no_identity)
                        Toast.makeText(ctx, getString(R.string.cloud_manager_toast_credentials_saved, username, port, identityLabel), Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(ctx,
                            getString(R.string.cloud_manager_toast_save_failed),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
