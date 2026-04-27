package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.ssh.forwarding.PortForwardingManager
import io.github.tabssh.ssh.forwarding.TunnelType
import io.github.tabssh.ui.adapters.TunnelAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Activity for managing SSH port forwarding tunnels
 * Allows users to create local, remote, and dynamic (SOCKS) tunnels
 */
class PortForwardingActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TunnelAdapter
    private lateinit var fab: FloatingActionButton

    // Tunnels are managed per-connection, so we need connection ID
    private var connectionId: String? = null
    private var portForwardingManager: PortForwardingManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_port_forwarding)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Port Forwarding"

        connectionId = intent.getStringExtra("connection_id")
        if (connectionId == null) {
            // Wave 3.3 — no connection passed in, treat as standalone launch
            // ("Background Tunnels" entry from the drawer). Pick a saved
            // connection, open an SSH session that persists in
            // SSHSessionManager, and proceed without a terminal.
            promptStandaloneConnection()
        } else {
            setupRecyclerView()
            setupFab()
            loadTunnels()
        }
    }

    private fun promptStandaloneConnection() {
        val app = application as io.github.tabssh.TabSSHApplication
        lifecycleScope.launch {
            val candidates = try {
                app.database.connectionDao().getRecentConnections(50)
            } catch (e: Exception) {
                Logger.e("PortForwardingActivity", "Recent fetch failed", e)
                emptyList()
            }
            if (candidates.isEmpty()) {
                runOnUiThread {
                    showError("No saved connections — add one first to use background tunnels.")
                    finish()
                }
                return@launch
            }
            runOnUiThread {
                val labels = candidates.map { it.getDisplayName() }.toTypedArray()
                MaterialAlertDialogBuilder(this@PortForwardingActivity)
                    .setTitle("Pick connection for background tunnels")
                    .setItems(labels) { _, which ->
                        attachStandaloneSession(candidates[which])
                    }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }

    private fun attachStandaloneSession(profile: io.github.tabssh.storage.database.entities.ConnectionProfile) {
        val app = application as io.github.tabssh.TabSSHApplication
        lifecycleScope.launch {
            val session = app.sshSessionManager.connectToServer(profile)
            if (session == null) {
                runOnUiThread {
                    showError("SSH connection failed — can't open background tunnels.")
                    finish()
                }
                return@launch
            }
            connectionId = profile.id
            runOnUiThread {
                supportActionBar?.title = "Port Forwarding · ${profile.getDisplayName()}"
                setupRecyclerView()
                setupFab()
                loadTunnels()
            }
        }
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recycler_tunnels)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = TunnelAdapter(
            onStart = { tunnel ->
                lifecycleScope.launch {
                    val ok = portForwardingManager?.startTunnel(tunnel.id) ?: false
                    loadTunnels()
                    // Wave 3.4 — auto-detect HTTP on the local end after a
                    // successful Local Forward; offer "Open in browser" if so.
                    if (ok && tunnel.type == TunnelType.LOCAL_FORWARD) {
                        val effectivePort = tunnel.actualLocalPort?.takeIf { it > 0 } ?: tunnel.localPort
                        offerBrowserOpenIfHttp(effectivePort)
                    }
                }
            },
            onStop = { tunnel ->
                lifecycleScope.launch {
                    portForwardingManager?.stopTunnel(tunnel.id)
                    loadTunnels()
                }
            },
            onDelete = { tunnel ->
                showDeleteConfirmation(tunnel.id)
            }
        )
        recyclerView.adapter = adapter
    }

    private fun setupFab() {
        fab = findViewById(R.id.fab_add_tunnel)
        fab.setOnClickListener {
            showTunnelTypeDialog()
        }
    }

    private fun loadTunnels() {
        lifecycleScope.launch {
            val tunnels = portForwardingManager?.getActiveTunnels() ?: emptyList()
            adapter.submitList(tunnels)
        }
    }

    private fun showTunnelTypeDialog() {
        val types = arrayOf("Local Forward (-L)", "Remote Forward (-R)", "Dynamic/SOCKS (-D)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Tunnel Type")
            .setItems(types) { _, which ->
                when (which) {
                    0 -> showLocalForwardDialog()
                    1 -> showRemoteForwardDialog()
                    2 -> showDynamicForwardDialog()
                }
            }
            .show()
    }

    private fun showLocalForwardDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_local_forward, null)
        val localPortEdit = dialogView.findViewById<EditText>(R.id.edit_local_port)
        val remoteHostEdit = dialogView.findViewById<EditText>(R.id.edit_remote_host)
        val remotePortEdit = dialogView.findViewById<EditText>(R.id.edit_remote_port)

        MaterialAlertDialogBuilder(this)
            .setTitle("Create Local Forward")
            .setMessage("Forward local port to remote host:port")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val localPort = localPortEdit.text.toString().toIntOrNull() ?: 0
                val remoteHost = remoteHostEdit.text.toString()
                val remotePort = remotePortEdit.text.toString().toIntOrNull() ?: 0

                if (localPort > 0 && remoteHost.isNotEmpty() && remotePort > 0) {
                    lifecycleScope.launch {
                        portForwardingManager?.createLocalForward(
                            localPort = localPort,
                            remoteHost = remoteHost,
                            remotePort = remotePort,
                            autoStart = true
                        )
                        loadTunnels()
                    }
                } else {
                    showError("Invalid port forwarding configuration")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoteForwardDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_remote_forward, null)
        val remotePortEdit = dialogView.findViewById<EditText>(R.id.edit_remote_port)
        val localHostEdit = dialogView.findViewById<EditText>(R.id.edit_local_host)
        val localPortEdit = dialogView.findViewById<EditText>(R.id.edit_local_port)

        // Pre-fill localhost
        localHostEdit.setText("localhost")

        MaterialAlertDialogBuilder(this)
            .setTitle("Create Remote Forward")
            .setMessage("Forward remote port to local host:port")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val remotePort = remotePortEdit.text.toString().toIntOrNull() ?: 0
                val localHost = localHostEdit.text.toString()
                val localPort = localPortEdit.text.toString().toIntOrNull() ?: 0

                if (remotePort > 0 && localHost.isNotEmpty() && localPort > 0) {
                    lifecycleScope.launch {
                        portForwardingManager?.createRemoteForward(
                            remotePort = remotePort,
                            localHost = localHost,
                            localPort = localPort,
                            autoStart = true
                        )
                        loadTunnels()
                    }
                } else {
                    showError("Invalid port forwarding configuration")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDynamicForwardDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_dynamic_forward, null)
        val localPortEdit = dialogView.findViewById<EditText>(R.id.edit_local_port)

        // Suggest common SOCKS port
        localPortEdit.setText("1080")

        MaterialAlertDialogBuilder(this)
            .setTitle("Create Dynamic Forward (SOCKS Proxy)")
            .setMessage("Create a SOCKS proxy on local port")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val localPort = localPortEdit.text.toString().toIntOrNull() ?: 0

                if (localPort > 0) {
                    lifecycleScope.launch {
                        portForwardingManager?.createDynamicForward(
                            localPort = localPort,
                            autoStart = true
                        )
                        loadTunnels()
                    }
                } else {
                    showError("Invalid port number")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(tunnelId: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Tunnel")
            .setMessage("Are you sure you want to delete this tunnel?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    portForwardingManager?.removeTunnel(tunnelId)
                    loadTunnels()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Wave 3.4 — Probe the freshly opened local port; if it's an HTTP server,
     * offer a one-tap "Open in browser" via a confirmation dialog.
     */
    private fun offerBrowserOpenIfHttp(localPort: Int) {
        if (localPort <= 0) return
        lifecycleScope.launch {
            val isHttp = io.github.tabssh.ssh.forwarding.HttpPortProbe.probe(localPort)
            if (!isHttp) return@launch
            val url = "http://127.0.0.1:$localPort/"
            MaterialAlertDialogBuilder(this@PortForwardingActivity)
                .setTitle("HTTP detected on :$localPort")
                .setMessage("Open $url in your browser?")
                .setPositiveButton("Open") { _, _ ->
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (e: Exception) {
                        Logger.w("PortForwardingActivity", "No browser to handle $url: ${e.message}")
                        showError("No browser available to open $url")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
