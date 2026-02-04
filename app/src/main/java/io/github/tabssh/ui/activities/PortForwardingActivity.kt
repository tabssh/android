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
            Logger.e("PortForwardingActivity", "No connection ID provided")
            finish()
            return
        }

        setupRecyclerView()
        setupFab()
        loadTunnels()
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recycler_tunnels)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = TunnelAdapter(
            onStart = { tunnel ->
                lifecycleScope.launch {
                    portForwardingManager?.startTunnel(tunnel.id)
                    loadTunnels()
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
}
