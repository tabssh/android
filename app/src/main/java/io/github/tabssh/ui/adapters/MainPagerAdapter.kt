package io.github.tabssh.ui.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.github.tabssh.ui.fragments.*

/**
 * ViewPager2 adapter for MainActivity's 5 tabs
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private val fragments = listOf(
        FrequentConnectionsFragment.newInstance(),
        ConnectionsFragment.newInstance(),
        IdentitiesFragment.newInstance(),
        PerformanceFragment.newInstance(),
        HypervisorsFragment.newInstance()
    )

    // Mobile-first labels — long words don't fit 5 tabs on a phone.
    // "Connections" → "Hosts", "Performance" → "Stats", "Hypervisors" →
    // "VMs". Tab content is unchanged; the underlying fragments still
    // refer to themselves with the longer names internally.
    private val tabTitles = listOf(
        "Frequent",
        "Hosts",
        "Identities",
        "Stats",
        "VMs"
    )

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]

    fun getTabTitle(position: Int): String = tabTitles.getOrElse(position) { "" }
}
