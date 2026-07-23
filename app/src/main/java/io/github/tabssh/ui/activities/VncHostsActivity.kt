package io.github.tabssh.ui.activities

import io.github.tabssh.sync.tombstone.TombstoneRecorder

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityVncHostsBinding
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated list screen for direct VNC host connections.
 * Moved out of the Connections fragment into its own Activity so VNC hosts
 * can be managed independently and have a top-level drawer entry.
 */
class VncHostsActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "VncHostsActivity"
    }

    private lateinit var binding: ActivityVncHostsBinding
    private lateinit var app: TabSSHApplication
    private lateinit var adapter: VncHostAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication

        binding = ActivityVncHostsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = VncHostAdapter(
            onConnect  = { host -> openVncConsole(host) },
            onLongPress = { host -> showHostMenu(host) }
        )
        binding.recyclerVncHosts.layoutManager = LinearLayoutManager(this)
        binding.recyclerVncHosts.adapter = adapter

        binding.fabAdd.setOnClickListener { launchAddHost() }

        // The "Add VNC Host" button inside the empty state also opens the add form.
        binding.emptyState.findViewById<MaterialButton>(R.id.button_add_first)
            .setOnClickListener { launchAddHost() }

        // Observe the database and update the list whenever it changes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.vncHostDao().getAllHosts().collect { hosts ->
                    adapter.submitList(hosts)
                    if (hosts.isEmpty()) {
                        binding.recyclerVncHosts.visibility = View.GONE
                        binding.emptyState.visibility = View.VISIBLE
                    } else {
                        binding.emptyState.visibility = View.GONE
                        binding.recyclerVncHosts.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    /** Launch VncHostEditActivity in "add new host" mode (no extra). */
    private fun launchAddHost() {
        startActivity(Intent(this, VncHostEditActivity::class.java))
    }

    /** Launch VncHostEditActivity in "edit existing host" mode. */
    private fun launchEditHost(host: VncHost) {
        startActivity(
            Intent(this, VncHostEditActivity::class.java).apply {
                putExtra(VncHostEditActivity.EXTRA_VNC_HOST_ID, host.id)
            }
        )
    }

    /**
     * Open the VNC console for the given host (VNC-tab-swipe integration
     * step 6c). Resolves credentials and connects the same way
     * [VMConsoleActivity.connectVncHost] does, then creates a [Tab.Vnc] on
     * the shared, application-scoped `TabManager` and focuses it in
     * [TabTerminalActivity] via [TabTerminalActivity.EXTRA_TAB_ID] — the
     * same "focus an existing/just-created tab" path already used for the
     * SSH "Active Sessions" tap (Issue #165). `TabTerminalActivity` never
     * needs to know how a VNC tab's session was established; it only
     * renders whatever [io.github.tabssh.ui.tabs.VncTab.rfbClient] is
     * already attached when the page binds.
     */
    private fun openVncConsole(host: VncHost) {
        lifecycleScope.launch {
            val (password, username) = withContext(Dispatchers.IO) {
                val identityId = host.identityId
                // Per-host password override always takes priority.
                val hostPw = try {
                    app.securePasswordManager.retrievePassword("vnc_host_${host.id}")
                } catch (e: Exception) {
                    Logger.w(TAG, "Could not retrieve VNC host password: ${e.message}")
                    null
                }
                if (hostPw != null) {
                    val identityUsername = if (identityId != null) {
                        app.database.vncIdentityDao().getById(identityId)?.username
                    } else null
                    Pair(hostPw, identityUsername)
                } else if (identityId != null) {
                    val identity = app.database.vncIdentityDao().getById(identityId)
                    val pw = try {
                        app.securePasswordManager.retrievePassword("vnc_identity_$identityId")
                    } catch (e: Exception) {
                        Logger.w(TAG, "Could not retrieve VNC identity password: ${e.message}")
                        null
                    }
                    Pair(pw, identity?.username)
                } else {
                    Pair(null, null)
                }
            }
            try {
                val (rfbClient, _) = withContext(Dispatchers.IO) {
                    io.github.tabssh.hypervisor.vnc.VncDirectConnector.connect(host, password, username, this@VncHostsActivity)
                }
                val tab = app.tabManager.createVncTab(host)
                if (tab == null) {
                    try { rfbClient.stop() } catch (e: Exception) {
                        Logger.d(TAG, "rfbClient.stop() suppressed after max-tabs reject: ${e.message}")
                    }
                    Toast.makeText(this@VncHostsActivity, "Maximum tabs reached", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                tab.rfbClient = rfbClient
                tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
                withContext(Dispatchers.IO) {
                    app.database.vncHostDao().updateLastConnected(host.id, System.currentTimeMillis())
                }
                startActivity(
                    Intent(this@VncHostsActivity, TabTerminalActivity::class.java).apply {
                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                    }
                )
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to connect to VNC host '${host.name}'", e)
                Toast.makeText(this@VncHostsActivity, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Long-press menu ───────────────────────────────────────────────────────

    /**
     * Show Edit / Delete options for a VNC host.
     * Delete shows a confirmation dialog before removing the row.
     */
    private fun showHostMenu(host: VncHost) {
        AlertDialog.Builder(this)
            .setTitle(host.name)
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                when (which) {
                    0 -> launchEditHost(host)
                    1 -> confirmDelete(host)
                }
            }
            .show()
    }

    private fun confirmDelete(host: VncHost) {
        AlertDialog.Builder(this)
            .setTitle("Delete '${host.name}'?")
            .setMessage("This removes the VNC host record. Any linked identity is not deleted.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.vncHostDao().deleteById(host.id)
                            // H6 — record the deletion so it propagates and is not resurrected.
                            TombstoneRecorder.record(app, TombstoneRecorder.VNC_HOST, host.id)
                        }
                        Logger.d(TAG, "Deleted VNC host: ${host.name}")
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to delete VNC host", e)
                        Toast.makeText(this@VncHostsActivity, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── RecyclerView adapter ─────────────────────────────────────────────────

    private inner class VncHostAdapter(
        private val onConnect:   (VncHost) -> Unit,
        private val onLongPress: (VncHost) -> Unit
    ) : ListAdapter<VncHost, VncHostAdapter.ViewHolder>(HostDiff) {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textName:    TextView      = view.findViewById(R.id.text_host_name)
            val textDetail:  TextView      = view.findViewById(R.id.text_host_detail)
            val btnConnect:  MaterialButton = view.findViewById(R.id.btn_connect)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vnc_host, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val host = getItem(position)
            holder.textName.text = host.name
            holder.textDetail.text = "${host.host}:${host.effectivePort}"
            holder.btnConnect.setOnClickListener { onConnect(host) }
            holder.itemView.setOnLongClickListener {
                onLongPress(host)
                true
            }
        }
    }

    private object HostDiff : DiffUtil.ItemCallback<VncHost>() {
        override fun areItemsTheSame(old: VncHost, new: VncHost) = old.id == new.id
        override fun areContentsTheSame(old: VncHost, new: VncHost) = old == new
    }
}
