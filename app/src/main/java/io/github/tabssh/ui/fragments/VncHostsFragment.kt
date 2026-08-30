package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
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
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.ui.activities.VncHostEditActivity
import io.github.tabssh.ui.utils.HostContextActions
import kotlinx.coroutines.launch
import io.github.tabssh.utils.tabSSHApp

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_vnc_hosts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = tabSSHApp

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

    /**
     * Same "resolve credentials, connect, create a [io.github.tabssh.ui.tabs.Tab.Vnc],
     * focus it in TabTerminalActivity" flow that `VncHostsActivity` used —
     * now shared via [HostContextActions.connectToVncHost] so search results
     * connect identically.
     */
    private fun openVncConsole(host: VncHost) {
        HostContextActions.connectToVncHost(
            fragment = this,
            app = app,
            host = host,
            isConnecting = { connecting },
            setConnecting = { connecting = it }
        )
    }

    private fun showHostMenu(host: VncHost) {
        HostContextActions.showVncHostMenu(this, app, host)
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
        fun newInstance() = VncHostsFragment()
    }
}
