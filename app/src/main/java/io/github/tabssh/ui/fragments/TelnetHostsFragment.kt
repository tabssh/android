package io.github.tabssh.ui.fragments

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.TelnetHost
import io.github.tabssh.sync.tombstone.TombstoneRecorder
import io.github.tabssh.ui.activities.ConnectionEditActivity
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * Hosts tab's Telnet sub-tab — list/CRUD for [TelnetHost] rows backed by
 * `TelnetHostDao`. Editing goes through [ConnectionEditActivity], which
 * already has full Telnet load/save support behind its protocol spinner;
 * there is no separate Telnet edit Activity to build. There is no live
 * Telnet connector in the app yet, so rows only support tap-to-edit — no
 * connect action is offered here.
 */
class TelnetHostsFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var adapter: TelnetHostAdapter

    private val isAlive: Boolean
        get() = isAdded && !requireActivity().isFinishing && !requireActivity().isDestroyed

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_telnet_hosts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = tabSSHApp

        recyclerView = view.findViewById(R.id.recycler_telnet_hosts)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = TelnetHostAdapter(
            onTap = { host -> launchEditHost(host) },
            onLongPress = { host -> showHostMenu(host) }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.fab_add_telnet_host).setOnClickListener { launchAddHost() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.telnetHostDao().getAll().collect { hosts ->
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
        startActivity(ConnectionEditActivity.createTelnetIntent(requireContext()))
    }

    private fun launchEditHost(host: TelnetHost) {
        startActivity(ConnectionEditActivity.createTelnetIntent(requireContext(), host.id))
    }

    private fun showHostMenu(host: TelnetHost) {
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

    private fun confirmDelete(host: TelnetHost) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.domain_delete_title, host.name))
            .setMessage(getString(R.string.telnet_host_delete_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.telnetHostDao().deleteById(host.id)
                            app.securePasswordManager.clearPassword(host.id)
                            TombstoneRecorder.record(app, TombstoneRecorder.TELNET_HOST, host.id)
                        }
                        Logger.d(TAG, "Deleted Telnet host: ${host.name}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to delete Telnet host", e)
                        if (!isAlive) return@launch
                        Toast.makeText(requireContext(), getString(R.string.domain_delete_failed_fmt, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private inner class TelnetHostAdapter(
        private val onTap: (TelnetHost) -> Unit,
        private val onLongPress: (TelnetHost) -> Unit
    ) : ListAdapter<TelnetHost, TelnetHostAdapter.ViewHolder>(HostDiff) {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textName: TextView = view.findViewById(R.id.text_host_name)
            val textDetail: TextView = view.findViewById(R.id.text_host_detail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_telnet_host, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val host = getItem(position)
            holder.textName.text = host.name
            holder.textDetail.text = getString(R.string.hypervisor_endpoint_fmt, host.host, host.port)
            holder.itemView.setOnClickListener { onTap(host) }
            holder.itemView.setOnLongClickListener {
                onLongPress(host)
                true
            }
        }
    }

    private object HostDiff : DiffUtil.ItemCallback<TelnetHost>() {
        override fun areItemsTheSame(old: TelnetHost, new: TelnetHost) = old.id == new.id
        override fun areContentsTheSame(old: TelnetHost, new: TelnetHost) = old == new
    }

    companion object {
        private const val TAG = "TelnetHostsFragment"
        fun newInstance() = TelnetHostsFragment()
    }
}
