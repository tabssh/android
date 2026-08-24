package io.github.tabssh.ui.fragments

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
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.ui.activities.TabTerminalActivity
import io.github.tabssh.ui.tabs.Tab
import io.github.tabssh.ui.tabs.connectedAt
import io.github.tabssh.ui.tabs.connectionState
import io.github.tabssh.ui.tabs.shortTitle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Intent

/**
 * Hosts tab's Active sub-tab — every currently-open [Tab] (SSH, VNC,
 * console, or panes group) app-wide, with a live connected-since timer.
 * This sub-tab is only inserted into the outer Hosts pager while at least
 * one tab is open; see `ConnectionsFragment`'s pager adapter for that.
 */
class ActiveHostsFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var adapter: ActiveTabAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_active_hosts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = requireActivity().application as TabSSHApplication

        recyclerView = view.findViewById(R.id.recycler_active_hosts)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = ActiveTabAdapter { tab -> openTab(tab) }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Combines the live tab list with a 1s ticker so the connected-since
        // timers advance even when no tab actually opens/closes.
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(1000)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.tabManager.allTabsFlow, ticker) { tabs, _ -> tabs }
                    .collect { tabs ->
                        adapter.submitList(tabs)
                        if (tabs.isEmpty()) {
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

    private fun openTab(tab: Tab) {
        app.tabManager.switchToTabById(tab.tabId)
        startActivity(
            Intent(requireContext(), TabTerminalActivity::class.java).apply {
                putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
            }
        )
    }

    private inner class ActiveTabAdapter(
        private val onTap: (Tab) -> Unit
    ) : ListAdapter<Tab, ActiveTabAdapter.ViewHolder>(TabDiff) {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val stateDot: View = view.findViewById(R.id.state_dot)
            val textTitle: TextView = view.findViewById(R.id.text_title)
            val textSubtitle: TextView = view.findViewById(R.id.text_subtitle)
            val textTimer: TextView = view.findViewById(R.id.text_timer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_active_host, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tab = getItem(position)
            val state = tab.connectionState()
            holder.textTitle.text = tab.shortTitle()
            holder.textSubtitle.text = getString(R.string.active_host_subtitle_fmt, protocolLabel(tab), state.displayName)
            holder.stateDot.setBackgroundResource(
                if (state == ConnectionState.CONNECTED) R.drawable.state_dot_connected else R.drawable.state_dot_disconnected
            )
            holder.textTimer.text = formatElapsed(tab.connectedAt())
            holder.itemView.setOnClickListener { onTap(tab) }
        }

        private fun protocolLabel(tab: Tab): String = when (tab) {
            is Tab.Ssh -> getString(R.string.hosts_search_badge_ssh)
            is Tab.Vnc -> getString(R.string.hosts_search_badge_vnc)
            is Tab.Console -> getString(R.string.hosts_search_badge_vnc)
            is Tab.Panes -> getString(R.string.hosts_search_badge_ssh)
        }

        private fun formatElapsed(connectedAt: Long?): String {
            if (connectedAt == null) return "--:--"
            val elapsedSeconds = ((System.currentTimeMillis() - connectedAt) / 1000).coerceAtLeast(0)
            val hours = elapsedSeconds / 3600
            val minutes = (elapsedSeconds % 3600) / 60
            val seconds = elapsedSeconds % 60
            return if (hours > 0) {
                String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }

    private object TabDiff : DiffUtil.ItemCallback<Tab>() {
        override fun areItemsTheSame(old: Tab, new: Tab) = old.tabId == new.tabId

        // Always false: the connected-since timer must re-render every
        // second even though the underlying Tab's own fields (state,
        // connectedAt) haven't changed between ticks.
        override fun areContentsTheSame(old: Tab, new: Tab) = false
    }

    companion object {
        fun newInstance() = ActiveHostsFragment()
    }
}
