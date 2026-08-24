package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ui.activities.TabTerminalActivity
import io.github.tabssh.ui.tabs.Tab
import io.github.tabssh.ui.tabs.connectionState
import io.github.tabssh.ui.tabs.shortTitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Hosts main tab — outer shell hosting four sub-tabs (Active, SSH, VNC,
 * Telnet) behind a ViewPager2, plus a single unified [SearchView] that
 * queries across all three host stores and the live tab list at once.
 *
 * The Active sub-tab only exists in the pager while at least one [Tab] is
 * open app-wide; [HostsPagerAdapter] tracks that with a stable-id scheme so
 * ViewPager2/FragmentStateAdapter can add or remove it without losing the
 * other sub-tabs' fragment instances.
 */
class ConnectionsFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var searchView: SearchView
    private lateinit var layoutSearchResults: View
    private lateinit var recyclerSearchResults: RecyclerView
    private lateinit var layoutSearchEmpty: View
    private lateinit var pagerAdapter: HostsPagerAdapter
    private lateinit var searchAdapter: SearchResultAdapter

    private var searchJob: Job? = null
    private var currentQuery: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_connections, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = requireActivity().application as TabSSHApplication

        viewPager = view.findViewById(R.id.view_pager_hosts)
        tabLayout = view.findViewById(R.id.tab_layout_hosts)
        searchView = view.findViewById(R.id.search_view_hosts)
        layoutSearchResults = view.findViewById(R.id.layout_search_results)
        recyclerSearchResults = view.findViewById(R.id.recycler_search_results)
        layoutSearchEmpty = view.findViewById(R.id.layout_search_empty)

        setupPager()
        setupSearch()
    }

    private fun setupPager() {
        val hasActiveTabs = app.tabManager.allTabsFlow.value.isNotEmpty()

        pagerAdapter = HostsPagerAdapter(this, hasActiveTabs)
        viewPager.adapter = pagerAdapter

        // The outer main-tab ViewPager2 already owns horizontal swipes;
        // letting this inner pager compete for the same gesture gets both
        // stuck. Tab taps on the TabLayout are the intended navigation here,
        // same pattern as InfraFragment's inner pager.
        viewPager.isUserInputEnabled = false

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getString(pagerAdapter.labelResAt(position))
        }.attach()

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val savedTabKey = prefs.getString(PREF_LAST_SUB_TAB, null)
        val labels = pagerAdapter.currentLabels()
        val initialPosition = when {
            savedTabKey != null && labels.contains(savedTabKey) -> labels.indexOf(savedTabKey)
            savedTabKey == null && labels.contains(TAB_ACTIVE) -> labels.indexOf(TAB_ACTIVE)
            else -> labels.indexOf(TAB_SSH).coerceAtLeast(0)
        }
        viewPager.setCurrentItem(initialPosition, false)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val key = pagerAdapter.currentLabels().getOrNull(position) ?: return
                prefs.edit().putString(PREF_LAST_SUB_TAB, key).apply()
            }
        })

        // Watches the live tab list so the Active sub-tab appears/disappears
        // without waiting for the user to leave and return to this fragment.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.tabManager.allTabsFlow.collect { tabs ->
                    val nowHasActive = tabs.isNotEmpty()
                    if (nowHasActive != pagerAdapter.hasActive) {
                        val wasOnActive = pagerAdapter.currentLabels().getOrNull(viewPager.currentItem) == TAB_ACTIVE
                        pagerAdapter.hasActive = nowHasActive
                        pagerAdapter.notifyDataSetChanged()
                        if (wasOnActive && !nowHasActive) {
                            val fallback = pagerAdapter.currentLabels().indexOf(TAB_SSH).coerceAtLeast(0)
                            viewPager.setCurrentItem(fallback, false)
                        }
                    }
                }
            }
        }
    }

    private fun setupSearch() {
        searchAdapter = SearchResultAdapter()
        recyclerSearchResults.layoutManager = LinearLayoutManager(requireContext())
        recyclerSearchResults.adapter = searchAdapter

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                runSearch(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                runSearch(newText.orEmpty())
                return true
            }
        })
    }

    private fun runSearch(query: String) {
        currentQuery = query.trim()
        searchJob?.cancel()

        if (currentQuery.isEmpty()) {
            layoutSearchResults.visibility = View.GONE
            viewPager.visibility = View.VISIBLE
            return
        }

        viewPager.visibility = View.GONE
        layoutSearchResults.visibility = View.VISIBLE

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            val results = buildSearchResults(currentQuery)
            searchAdapter.submitList(results)
            layoutSearchEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            recyclerSearchResults.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private suspend fun buildSearchResults(query: String): List<SearchResult> {
        val needle = query.lowercase()
        val results = mutableListOf<SearchResult>()

        app.database.connectionDao().getAllConnectionsList()
            .filter { it.protocol == "ssh" }
            .filter { it.name.lowercase().contains(needle) || it.host.lowercase().contains(needle) }
            .forEach { connection ->
                results += SearchResult(
                    badge = getString(R.string.hosts_search_badge_ssh),
                    title = connection.name,
                    subtitle = getString(R.string.hypervisor_endpoint_fmt, connection.host, connection.port)
                ) {
                    startActivity(
                        io.github.tabssh.ui.activities.ConnectionEditActivity
                            .createIntent(requireContext(), connection.id)
                    )
                }
            }

        app.database.vncHostDao().getAllHostsList()
            .filter { it.name.lowercase().contains(needle) || it.host.lowercase().contains(needle) }
            .forEach { host ->
                results += SearchResult(
                    badge = getString(R.string.hosts_search_badge_vnc),
                    title = host.name,
                    subtitle = getString(R.string.hypervisor_endpoint_fmt, host.host, host.effectivePort)
                ) {
                    startActivity(
                        Intent(requireContext(), io.github.tabssh.ui.activities.VncHostEditActivity::class.java).apply {
                            putExtra(io.github.tabssh.ui.activities.VncHostEditActivity.EXTRA_VNC_HOST_ID, host.id)
                        }
                    )
                }
            }

        app.database.telnetHostDao().getAllList()
            .filter { it.name.lowercase().contains(needle) || it.host.lowercase().contains(needle) }
            .forEach { host ->
                results += SearchResult(
                    badge = getString(R.string.hosts_search_badge_telnet),
                    title = host.name,
                    subtitle = getString(R.string.hypervisor_endpoint_fmt, host.host, host.port)
                ) {
                    startActivity(
                        io.github.tabssh.ui.activities.ConnectionEditActivity
                            .createTelnetIntent(requireContext(), host.id)
                    )
                }
            }

        app.tabManager.getAllTabsSealed()
            .filter { it.shortTitle().lowercase().contains(needle) }
            .forEach { tab ->
                results += SearchResult(
                    badge = getString(R.string.hosts_search_badge_active),
                    title = tab.shortTitle(),
                    subtitle = tab.connectionState().displayName
                ) {
                    app.tabManager.switchToTabById(tab.tabId)
                    startActivity(
                        Intent(requireContext(), TabTerminalActivity::class.java).apply {
                            putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                        }
                    )
                }
            }

        return results
    }

    private data class SearchResult(
        val badge: String,
        val title: String,
        val subtitle: String,
        val onClick: () -> Unit
    )

    private class SearchResultAdapter :
        RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

        private var items: List<SearchResult> = emptyList()

        fun submitList(newItems: List<SearchResult>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textBadge: TextView = view.findViewById(R.id.text_badge)
            val textTitle: TextView = view.findViewById(R.id.text_title)
            val textSubtitle: TextView = view.findViewById(R.id.text_subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.textBadge.text = item.badge
            holder.textTitle.text = item.title
            holder.textSubtitle.text = item.subtitle
            holder.itemView.setOnClickListener { item.onClick() }
        }

        override fun getItemCount(): Int = items.size
    }

    /**
     * Sub-tab pager with a dynamic item count — Active is included only
     * while [hasActive] is true. Stable per-label IDs (rather than plain
     * position) let ViewPager2/FragmentStateAdapter tell that SSH/VNC/Telnet
     * are the *same* fragments across an Active insert/remove instead of
     * recreating every fragment whenever the count changes.
     */
    private class HostsPagerAdapter(
        fragment: Fragment,
        hasActive: Boolean
    ) : FragmentStateAdapter(fragment) {

        var hasActive: Boolean = hasActive

        fun currentLabels(): List<String> =
            if (hasActive) listOf(TAB_ACTIVE, TAB_SSH, TAB_VNC, TAB_TELNET) else listOf(TAB_SSH, TAB_VNC, TAB_TELNET)

        fun labelResAt(position: Int): Int = when (currentLabels()[position]) {
            TAB_ACTIVE -> R.string.hosts_sub_tab_active
            TAB_SSH -> R.string.hosts_sub_tab_ssh
            TAB_VNC -> R.string.hosts_sub_tab_vnc
            TAB_TELNET -> R.string.hosts_sub_tab_telnet
            else -> error("Unknown Hosts sub-tab label at position $position")
        }

        override fun getItemCount(): Int = currentLabels().size

        override fun createFragment(position: Int): Fragment = when (currentLabels()[position]) {
            TAB_ACTIVE -> ActiveHostsFragment.newInstance()
            TAB_SSH -> SshHostsFragment.newInstance()
            TAB_VNC -> VncHostsFragment.newInstance()
            TAB_TELNET -> TelnetHostsFragment.newInstance()
            else -> error("Unknown Hosts sub-tab label at position $position")
        }

        override fun getItemId(position: Int): Long = idFor(currentLabels()[position])

        override fun containsItem(itemId: Long): Boolean = currentLabels().any { idFor(it) == itemId }

        private fun idFor(label: String): Long = when (label) {
            TAB_ACTIVE -> 1L
            TAB_SSH -> 2L
            TAB_VNC -> 3L
            TAB_TELNET -> 4L
            else -> error("Unknown Hosts sub-tab label: $label")
        }
    }

    companion object {
        private const val PREF_LAST_SUB_TAB = "ui_last_hosts_sub_tab_key"
        private const val TAB_ACTIVE = "active"
        private const val TAB_SSH = "ssh"
        private const val TAB_VNC = "vnc"
        private const val TAB_TELNET = "telnet"

        fun newInstance() = ConnectionsFragment()
    }
}
