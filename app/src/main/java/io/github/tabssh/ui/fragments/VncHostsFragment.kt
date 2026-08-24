package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.sync.tombstone.TombstoneRecorder
import io.github.tabssh.ui.activities.TabTerminalActivity
import io.github.tabssh.ui.activities.VncHostEditActivity
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts tab's VNC sub-tab — relocated from the standalone `VncHostsActivity`
 * (formerly reachable from the drawer's "Accounts" group) so VNC hosts live
 * alongside SSH/Telnet/Active in one Hosts tab. Reuses [VncHost]/[VncHostDao]
 * and [VncHostEditActivity] as-is; only the list/CRUD host moved.
 */
class VncHostsFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var adapter: VncHostAdapter

    // Connecting suspends through the whole RFB handshake while the row's
    // connect button stays tappable — a double tap would open two sockets.
    private var connecting = false

    private val isAlive: Boolean
        get() = isAdded && !requireActivity().isFinishing && !requireActivity().isDestroyed

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_vnc_hosts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = requireActivity().application as TabSSHApplication

        recyclerView = view.findViewById(R.id.recycler_vnc_hosts)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = VncHostAdapter(
            onConnect = { host -> openVncConsole(host) },
            onLongPress = { host -> showHostMenu(host) }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.fab_add_vnc_host).setOnClickListener { launchAddHost() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.vncHostDao().getAllHosts().collect { hosts ->
                    adapter.submitList(hosts)
                    if (hosts.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        emptyState.visibility = View.VISIBLE
                    } else {
                        emptyState.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun launchAddHost() {
        startActivity(Intent(requireContext(), VncHostEditActivity::class.java))
    }

    private fun launchEditHost(host: VncHost) {
        startActivity(
            Intent(requireContext(), VncHostEditActivity::class.java).apply {
                putExtra(VncHostEditActivity.EXTRA_VNC_HOST_ID, host.id)
            }
        )
    }

    /**
     * Same "resolve credentials, connect, create a [io.github.tabssh.ui.tabs.Tab.Vnc],
     * focus it in TabTerminalActivity" flow that `VncHostsActivity` used.
     */
    private fun openVncConsole(host: VncHost) {
        if (connecting) return
        connecting = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (password, username) = withContext(Dispatchers.IO) {
                    val identityId = host.identityId
                    val hostPw = try {
                        app.securePasswordManager.retrievePassword("vnc_host_${host.id}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
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
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.w(TAG, "Could not retrieve VNC identity password: ${e.message}")
                            null
                        }
                        Pair(pw, identity?.username)
                    } else {
                        Pair(null, null)
                    }
                }
                val (rfbClient, _) = withContext(Dispatchers.IO) {
                    io.github.tabssh.hypervisor.vnc.VncDirectConnector.connect(host, password, username, requireContext())
                }
                val tab = app.tabManager.createVncTab(host)
                if (tab == null) {
                    try { rfbClient.stop() } catch (e: Exception) {
                        Logger.d(TAG, "rfbClient.stop() suppressed after max-tabs reject: ${e.message}")
                    }
                    if (!isAlive) return@launch
                    Toast.makeText(requireContext(), R.string.virt_viewer_max_tabs, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                tab.rfbClient = rfbClient
                tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
                withContext(Dispatchers.IO) {
                    app.database.vncHostDao().updateLastConnected(host.id, System.currentTimeMillis())
                }
                if (!isAlive) return@launch
                startActivity(
                    Intent(requireContext(), TabTerminalActivity::class.java).apply {
                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to connect to VNC host '${host.name}'", e)
                if (!isAlive) return@launch
                Toast.makeText(requireContext(), getString(R.string.virt_viewer_connect_failed, e.message), Toast.LENGTH_LONG).show()
            } finally {
                connecting = false
            }
        }
    }

    private fun showHostMenu(host: VncHost) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(host.name)
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> launchEditHost(host)
                    1 -> confirmDelete(host)
                }
            }
            .show()
    }

    private fun confirmDelete(host: VncHost) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.vnc_host_delete_title, host.name))
            .setMessage(getString(R.string.vnc_host_delete_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.vncHostDao().deleteById(host.id)
                            app.securePasswordManager.clearPassword("vnc_host_${host.id}")
                            TombstoneRecorder.record(app, TombstoneRecorder.VNC_HOST, host.id)
                        }
                        Logger.d(TAG, "Deleted VNC host: ${host.name}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to delete VNC host", e)
                        if (!isAlive) return@launch
                        Toast.makeText(requireContext(), getString(R.string.vnc_host_delete_failed_fmt, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private inner class VncHostAdapter(
        private val onConnect: (VncHost) -> Unit,
        private val onLongPress: (VncHost) -> Unit
    ) : ListAdapter<VncHost, VncHostAdapter.ViewHolder>(HostDiff) {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textName: TextView = view.findViewById(R.id.text_host_name)
            val textDetail: TextView = view.findViewById(R.id.text_host_detail)
            val btnConnect: MaterialButton = view.findViewById(R.id.btn_connect)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vnc_host, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val host = getItem(position)
            holder.textName.text = host.name
            holder.textDetail.text = getString(R.string.hypervisor_endpoint_fmt, host.host, host.effectivePort)
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

    companion object {
        private const val TAG = "VncHostsFragment"
        fun newInstance() = VncHostsFragment()
    }
}
